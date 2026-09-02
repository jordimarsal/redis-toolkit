package net.jordimp.redistoolkit.jobqueue.infra.redis;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.JobId;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.port.PendingStats;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.port.SubmitResult;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RedisQueueStore implements QueueStore {

    private static final int SEEN_TTL_SECONDS = 60;

    private final String queueName;
    private final JedisPool pool;

    public RedisQueueStore(JedisPool pool) {
        this.pool = pool;
        this.queueName = "default";
    }

    public RedisQueueStore(JedisPool pool, String queueName) {
        this.pool = pool;
        this.queueName = (queueName == null || queueName.isBlank()) ? "default" : queueName;
    }

    @Override
    public SubmitResult submit(Payload payload, Priority priority, String dedupKey) {
        String streamKey = streamKey(priority);
        Map<String, String> fields = new HashMap<>();
        fields.put("payload", Base64.getEncoder().encodeToString(payload.data()));
        if (dedupKey != null) {
            fields.put("dedup", dedupKey);
        }
        String id;
        try (Jedis jedis = pool.getResource()) {
            id = jedis.xadd(streamKey, XAddParams.xAddParams(), fields).toString();
            jedis.hset(jobHash(id), "stream", streamKey);
            jedis.hset(jobHash(id), "payload", fields.get("payload"));
            if (fields.containsKey("dedup")) {
                jedis.hset(jobHash(id), "dedup", fields.get("dedup"));
            }
        }
        return new SubmitResult(queueName, JobId.of(id));
    }

    @Override
    public SubmitResult submitDelayed(Payload payload, Priority priority, String dedupKey, java.time.Instant runAt) {
        String member = priority.name() + "|" + (dedupKey == null ? "" : dedupKey) + "|" + Base64.getEncoder().encodeToString(payload.data());
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd(delayKey(), runAt.toEpochMilli(), member);
        }
        return new SubmitResult(queueName, JobId.generate());
    }

    @Override
    public Optional<ClaimedJob> claim(String groupId, int maxPoll) {
        int scanned = 0;
        for (Priority p : new Priority[]{Priority.HIGH, Priority.NORMAL, Priority.LOW}) {
            String streamKey = streamKey(p);
            if (scanned >= maxPoll) {
                break;
            }
            List<StreamEntry> entries;
            try (Jedis jedis = pool.getResource()) {
                ensureGroup(streamKey, groupId);
                Map<String, StreamEntryID> ids = Map.of(streamKey, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY);
                XReadGroupParams params = XReadGroupParams.xReadGroupParams().count(1);
                Map<String, List<StreamEntry>> result = jedis.xreadGroupAsMap(redisGroup(groupId), streamKey, params, ids);
                List<StreamEntry> list = (result == null) ? List.of() : result.getOrDefault(streamKey, List.of());
                entries = (list == null || list.isEmpty()) ? List.of() : list;
            }
            if (entries.isEmpty()) {
                continue;
            }
            scanned++;
            StreamEntry entry = entries.get(0);
            String id = entry.getID().toString();
            Map<String, String> fields = entry.getFields();
            String dedupKey = fields.get("dedup");
            if (dedupKey != null && seenExists(dedupKey)) {
                continue;
            }
            if (dedupKey != null) {
                markSeen(dedupKey);
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.sadd(pendingKey(groupId), id);
                jedis.hset(jobMapHash(id), "stream", streamKey);
            }
            Payload payload = Payload.of(Base64.getDecoder().decode(fields.get("payload")));
            return Optional.of(new ClaimedJob(JobId.of(id), payload, dedupKey, 1));
        }
        return Optional.empty();
    }

    @Override
    public void acknowledge(String groupId, ClaimedJob claimed) {
        try (Jedis jedis = pool.getResource()) {
            String streamKey = jedis.hget(jobMapHash(claimed.jobId().raw()), "stream");
            if (streamKey != null) {
                jedis.xack(streamKey, redisGroup(groupId), new StreamEntryID(claimed.jobId().raw()));
            }
            jedis.srem(pendingKey(groupId), claimed.jobId().raw());
        }
    }

    @Override
    public int reclaimPending(int maxClaim) {
        int moved = 0;
        Set<String> pendingKeys;
        try (Jedis jedis = pool.getResource()) {
            pendingKeys = jedis.keys(pendingPattern());
        }
        for (String pendingKey : pendingKeys) {
            List<String> ids;
            try (Jedis jedis = pool.getResource()) {
                ids = new ArrayList<>(jedis.smembers(pendingKey));
            }
            for (String id : new ArrayList<>(ids)) {
                if (moved >= maxClaim) {
                    break;
                }
                String streamKey;
                try (Jedis jedis = pool.getResource()) {
                    streamKey = jedis.hget(jobMapHash(id), "stream");
                }
                if (streamKey == null || streamKey.isEmpty()) {
                    continue;
                }
                Map<String, String> fields;
                try (Jedis jedis = pool.getResource()) {
                    fields = jedis.hgetAll(jobHash(id));
                }
                try (Jedis jedis = pool.getResource()) {
                    jedis.xadd(streamKey, XAddParams.xAddParams(), fields);
                    jedis.del(jobMapHash(id));
                }
                try (Jedis jedis = pool.getResource()) {
                    jedis.xack(streamKey, redisGroup(groupOf(pendingKey)), new StreamEntryID(id));
                    jedis.srem(pendingKey, id);
                }
                moved++;
            }
        }
        return moved;
    }

    @Override
    public PendingStats pendingStats() {
        long total = 0L;
        Set<String> pendingKeys;
        try (Jedis jedis = pool.getResource()) {
            pendingKeys = jedis.keys(pendingPattern());
        }
        for (String pendingKey : pendingKeys) {
            try (Jedis jedis = pool.getResource()) {
                total += jedis.scard(pendingKey);
            }
        }
        return new PendingStats(total, total);
    }

    @Override
    public int promoteDelayed() {
        long now = System.currentTimeMillis();
        List<String> members;
        try (Jedis jedis = pool.getResource()) {
            members = jedis.zrangeByScore(delayKey(), Double.NEGATIVE_INFINITY, (double) now);
        }
        if (members.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        for (String member : members) {
            String[] parts = member.split("\\|", -1);
            Priority priority = Priority.valueOf(parts[0]);
            String dedupKey = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
            byte[] payloadBytes = Base64.getDecoder().decode(parts[2]);
            Map<String, String> fields = new HashMap<>();
            fields.put("payload", new String(Base64.getEncoder().encodeToString(payloadBytes)));
            if (dedupKey != null) {
                fields.put("dedup", dedupKey);
            }
            String streamKey = streamKey(priority);
            try (Jedis jedis = pool.getResource()) {
                String id = jedis.xadd(streamKey, XAddParams.xAddParams(), fields).toString();
                jedis.hset(jobHash(id), "stream", streamKey);
                jedis.hset(jobHash(id), "payload", fields.get("payload"));
                if (dedupKey != null) {
                    jedis.hset(jobHash(id), "dedup", dedupKey);
                }
                promoted++;
            }
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.zrem(delayKey(), members.toArray(new String[0]));
        }
        return promoted;
    }

    private void ensureGroup(String streamKey, String groupId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xgroupCreate(streamKey, redisGroup(groupId), new StreamEntryID(0L, 0L), true);
        } catch (RuntimeException ignored) {
            // group already exists
        }
    }

    private boolean seenExists(String dedupKey) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(seenKey(dedupKey));
        }
    }

    private void markSeen(String dedupKey) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(seenKey(dedupKey), SEEN_TTL_SECONDS, "1");
        }
    }

    private String redisGroup(String groupId) {
        return queueName + ":" + groupId;
    }

    private String groupOf(String pendingKey) {
        int suffix = pendingKey.lastIndexOf(':');
        return queueName + ":" + pendingKey.substring(suffix + 1);
    }

    private String streamKey(Priority p) {
        return "jobqueue:" + queueName + ":" + p.name();
    }

    private String pendingKey(String groupId) {
        return "jobqueue:" + queueName + ":pending:" + groupId;
    }

    private String pendingPattern() {
        return "jobqueue:" + queueName + ":pending:*";
    }

    private String delayKey() {
        return "jobqueue:" + queueName + ":delay";
    }

    private String jobHash(String id) {
        return "jobqueue:" + queueName + ":job:" + id;
    }

    private String jobMapHash(String id) {
        return "jobqueue:" + queueName + ":jobmap:" + id;
    }

    private String seenKey(String dedupKey) {
        return "jobqueue:" + queueName + ":seen:" + dedupKey;
    }

    @Override
    public void close() {
        pool.close();
    }
}
