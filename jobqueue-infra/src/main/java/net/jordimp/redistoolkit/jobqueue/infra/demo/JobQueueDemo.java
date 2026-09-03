package net.jordimp.redistoolkit.jobqueue.infra.demo;

import net.jordimp.redistoolkit.jobqueue.domain.DedupKey;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.infra.memory.InMemoryQueueStore;
import net.jordimp.redistoolkit.jobqueue.infra.metrics.InMemoryMetrics;
import net.jordimp.redistoolkit.jobqueue.infra.redis.RedisQueueStore;
import net.jordimp.redistoolkit.jobqueue.port.PendingStats;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.usecase.WorkerLoop;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runnable demonstration of the distributed job queue. It submits a small workload (mixed priorities, an
 * idempotent/deduplicated pair and a delayed job), promotes the delayed entry and runs the real
 * {@link WorkerLoop} to drain it, printing the delivery order and metrics.
 *
 * <p>It is offline by default: with no {@code REDIS_HOST} it uses {@link InMemoryQueueStore}, so it runs on
 * any JVM with nothing but the JDK. Point {@code REDIS_HOST}/{@code REDIS_PASSWORD} at the Redis from
 * {@code docker-compose.yml} (service {@code redis}, password {@code redis-dev-password}) and it exercises
 * the real streams + consumer groups end to end.
 */
public final class JobQueueDemo {

    private static final String GROUP = "demo-group";
    private static final int REDIS_SOCKET_TIMEOUT_MS = 10_000;
    private static final int REDIS_CONNECT_TIMEOUT_MS = 10_000;

    public static void main(String[] args) {
        QueueStore store = createStore();
        InMemoryMetrics metrics = new InMemoryMetrics();
        WorkerLoop worker = new WorkerLoop(GROUP, store, metrics);

        System.out.println("jobqueue demo — using " + (store instanceof RedisQueueStore ? "Redis streams" : "in-memory store")
                + "; consumer group '" + GROUP + "'");

        submitWorkload(store);
        store.promoteDelayed();

        List<String> processed = new CopyOnWriteArrayList<>();
        worker.setProcessor(job -> processed.add(new String(job.payload().data())));
        worker.pollAndProcess(Integer.MAX_VALUE);

        printSummary(store, metrics, processed);

        try {
            store.close();
        } catch (RuntimeException e) {
            System.out.println("[demo] could not cleanly close store: " + e.getMessage());
        }
    }

    /** Priorities, an idempotent pair and a delayed job that is promoted before the loop drains. */
    static void submitWorkload(QueueStore store) {
        System.out.println("[demo] submitting workload (priorities + dedup + delayed)...");
        // Submitted last but must be consumed first: delivery follows priority, not arrival order.
        store.submit(Payload.of("low-priority-job".getBytes()), Priority.LOW, null);
        store.submit(Payload.of("normal-job".getBytes()), Priority.NORMAL, null);
        store.submit(Payload.of("high-priority-job".getBytes()), Priority.HIGH, null);
        // Two identical submissions with the same dedup key collapse into a single delivery.
        DedupKey dedup = DedupKey.of("dedup-1");
        store.submit(Payload.of("idempotent-work".getBytes()), Priority.NORMAL, dedup);
        store.submit(Payload.of("idempotent-work".getBytes()), Priority.NORMAL, dedup);
        // Scheduled for the past so it becomes due immediately once promoted.
        store.submitDelayed(Payload.of("delayed-job".getBytes()), Priority.NORMAL, null, Instant.now().minusSeconds(30));
    }

    static void printSummary(QueueStore store, InMemoryMetrics metrics, List<String> processed) {
        System.out.println("-----------------------------------------------");
        System.out.println("processed " + processed.size() + " job(s), in priority order:");
        for (String text : processed) {
            System.out.println("  - " + text);
        }
        System.out.println("delivered=" + metrics.count(GROUP, "delivered")
                + " failed=" + metrics.count(GROUP, "failed"));
        PendingStats stats = store.pendingStats();
        System.out.println("pending unacked=" + stats.unackedEntries());
        System.out.println("(the two identical submissions collapsed into one delivery; the delayed job was promoted and delivered too)");
    }

    /** In-memory when {@code REDIS_HOST} is unset; otherwise a pooled Redis adapter honouring AUTH. */
    static QueueStore createStore() {
        String host = System.getenv("REDIS_HOST");
        if (host == null || host.isBlank()) {
            return new InMemoryQueueStore("demo");
        }
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(REDIS_CONNECT_TIMEOUT_MS)
                .socketTimeoutMillis(REDIS_SOCKET_TIMEOUT_MS)
                .password(trimmedEnv("REDIS_PASSWORD"))
                .build();
        JedisPool pool = new JedisPool(poolConfig, new HostAndPort(host, parseRedisPort()), clientConfig);
        return new RedisQueueStore(pool, "demo");
    }

    private static int parseRedisPort() {
        String raw = System.getenv("REDIS_PORT");
        if (raw != null && !raw.isBlank()) {
            return Integer.parseInt(raw.trim());
        }
        return 6379;
    }

    private static String trimmedEnv(String name) {
        String raw = System.getenv(name);
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }
}
