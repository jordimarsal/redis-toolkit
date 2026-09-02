package net.jordimp.redistoolkit.jobqueue.infra.redis;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.DedupKey;
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

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RedisQueueStore implements QueueStore {

    private static final int SEEN_TTL_SECONDS = 60;

    private static final String SEEN_CHECK_AND_SET =
        "if redis.call('EXISTS', KEYS[1]) == 1 then return '0' end "
        + "redis.call('SETEX', KEYS[1], ARGV[1], '1') return '1'";

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
    public SubmitResult submit(Payload payload, Priority priority, DedupKey dedupKey) {
        String streamKey = streamKey(priority);
        Map<String, String> fields = new HashMap<>();
        fields.put("payload", Base64.getEncoder().encodeToString(payload.data()));
        if (dedupKey != null) {
            fields.put("dedup", dedupKey.raw());
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
    public SubmitResult submitDelayed(Payload payload, Priority priority, DedupKey dedupKey, java.time.Instant runAt) {
        // The 4th part carries the provisional id; PROMOTE_ONE maps it to the real stream entry id.
        JobId provisional = JobId.generate();
        String member = priority.name() + "|" + (dedupKey == null ? "" : dedupKey.raw()) + "|"
                + Base64.getEncoder().encodeToString(payload.data()) + "|" + provisional.raw();
        try (Jedis jedis = pool.getResource()) {
            jedis.zadd(delayKey(), runAt.toEpochMilli(), member);
        }
        return new SubmitResult(queueName, provisional);
    }

    @Override
    public Optional<JobId> resolveDelayed(JobId provisional) {
        try (Jedis jedis = pool.getResource()) {
            String real = jedis.hget(delayMapKey(), provisional.raw());
            return real == null ? Optional.empty() : Optional.of(JobId.of(real));
        }
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
            if (dedupKey != null && !isNewlyMarkedSeen(dedupKey)) {
                // Skip AND acknowledge: a duplicate left unacked would sit in the consumer PEL forever.
                try (Jedis jedis = pool.getResource()) {
                    jedis.xack(streamKey, redisGroup(groupId), new StreamEntryID(id));
                }
                continue;
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.sadd(pendingKey(groupId), id);
                jedis.sadd(pendingIndexKey(), pendingKey(groupId));
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

    /**
     * Atomically pops up to ARGV[1] entry ids from the pending set. SPOP removes them as a side
     * effect, so no concurrent reclaimer can grab the same id twice and no explicit SREM is needed.
     */
    private static final String RECLAIM_ONE_SCRIPT = """
            local ids = {}
            for i = 1, tonumber(ARGV[1]) do
              local id = redis.call('SPOP', KEYS[1])
              if id == false then break end
              table.insert(ids, id)
            end
            return ids
            """;

    @Override
    public int reclaimPending(int maxClaim) {
        int moved = 0;
        Set<String> pendingKeys = collectPendingGroupKeys();
        for (String pendingKey : pendingKeys) {
            if (moved >= maxClaim) {
                break;
            }
            Object result;
            try (Jedis jedis = pool.getResource()) {
                result = jedis.eval(RECLAIM_ONE_SCRIPT, 1, pendingKey, String.valueOf(maxClaim - moved));
            }
            if (!(result instanceof List<?> claimedIds)) {
                continue; // empty Lua table comes back as nil
            }
            for (Object o : claimedIds) {
                String id = (String) o;
                if (!readdClean(id, streamOf(id), groupOf(pendingKey))) {
                    continue;
                }
                moved++;
                if (moved >= maxClaim) {
                    break;
                }
            }
        }
        return moved;
    }

    /** Re-adds the entry with only its business fields and acknowledges the old one. */
    private boolean readdClean(String id, String streamKey, String groupId) {
        Map<String, String> fields;
        try (Jedis jedis = pool.getResource()) {
            fields = new HashMap<>(jedis.hgetAll(jobHash(id)));
        }
        fields.remove("stream"); // internal bookkeeping must never leak into a redelivered entry
        if (streamKey == null || streamKey.isEmpty() || fields.isEmpty()) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(streamKey, XAddParams.xAddParams(), fields);
            jedis.del(jobMapHash(id));
            jedis.xack(streamKey, redisGroup(groupId), new StreamEntryID(id));
        }
        return true;
    }

    private String streamOf(String id) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hget(jobMapHash(id), "stream");
        }
    }

    @Override
    public PendingStats pendingStats() {
        long total = 0L;
        Set<String> pendingKeys = collectPendingGroupKeys();
        for (String pendingKey : pendingKeys) {
            try (Jedis jedis = pool.getResource()) {
                total += jedis.scard(pendingKey);
            }
        }
        return new PendingStats(total, total);
    }

    /**
     * Atomically pops the first due member and promotes it: ZRANGEBYSCORE + ZREM + XADD + job
     * metadata (+ provisional->real mapping for delayed jobs) in a single script, so two
     * concurrent promoters can never deliver the same job twice. Returns 'INVALID' when the
     * member is corrupt (it has already been removed from the zset).
     */
    private static final String PROMOTE_ONE_SCRIPT = """
            local m = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 1)
            if #m == 0 then return nil end
            redis.call('ZREM', KEYS[1], m[1])
            local parts = {}
            local start = 1
            while true do
              local pos = string.find(m[1], '|', start, true)
              if not pos then break end
              table.insert(parts, string.sub(m[1], start, pos - 1))
              start = pos + 1
            end
            table.insert(parts, string.sub(m[1], start))
            local priorityName = parts[1]
            if priorityName ~= 'HIGH' and priorityName ~= 'NORMAL' and priorityName ~= 'LOW' then
              return 'INVALID'
            end
            local payloadB64 = parts[3]
            if payloadB64 == nil or payloadB64 == '' then return 'INVALID' end
            local streamKey = 'jobqueue:' .. ARGV[2] .. ':' .. priorityName
            local args = { streamKey, '*', 'payload', payloadB64 }
            if parts[2] ~= '' then
              table.insert(args, 'dedup')
              table.insert(args, parts[2])
            end
            local id = redis.call('XADD', unpack(args))
            local jobHashKey = 'jobqueue:' .. ARGV[2] .. ':job:' .. id
            redis.call('HSET', jobHashKey, 'stream', streamKey, 'payload', payloadB64)
            if parts[2] ~= '' then
              redis.call('HSET', jobHashKey, 'dedup', parts[2])
            end
            local provisional = (#parts >= 4 and parts[4] ~= '') and parts[4] or ''
            if provisional ~= '' then
              redis.call('HSET', 'jobqueue:' .. ARGV[2] .. ':delaymap', provisional, id)
            end
            return { id, provisional }
            """;

    @Override
    public int promoteDelayed() {
        long now = System.currentTimeMillis();
        int promoted = 0;
        while (true) {
            Object result;
            try (Jedis jedis = pool.getResource()) {
                result = jedis.eval(PROMOTE_ONE_SCRIPT, 1, delayKey(), String.valueOf(now), queueName);
            }
            if (result == null) {
                break; // no more due members
            }
            if ("INVALID".equals(result)) {
                continue; // corrupt member discarded; keep draining what is due
            }
            @SuppressWarnings("unchecked")
            List<String> pair = (List<String>) result;
            promoted++;
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

    private boolean isNewlyMarkedSeen(String dedupKey) {
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(SEEN_CHECK_AND_SET, 1, seenKey(dedupKey), String.valueOf(SEEN_TTL_SECONDS));
            return "1".equals(String.valueOf(result));
        }
    }

    /** Explicit index set instead of a key-space SCAN: O(1) lookup and no full-DB iteration per call. */
    private Set<String> collectPendingGroupKeys() {
        try (Jedis jedis = pool.getResource()) {
            return new HashSet<>(jedis.smembers(pendingIndexKey()));
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

    private String pendingIndexKey() {
        return "jobqueue:" + queueName + ":pending-index";
    }

    private String delayKey() {
        return "jobqueue:" + queueName + ":delay";
    }

    private String delayMapKey() {
        return "jobqueue:" + queueName + ":delaymap";
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
