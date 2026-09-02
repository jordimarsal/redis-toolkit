package net.jordimp.redistoolkit.jobqueue.infra.redis;

import net.jordimp.redistoolkit.jobqueue.contract.QueueStoreContractTest;
import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.DedupKey;
import net.jordimp.redistoolkit.jobqueue.domain.JobId;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.port.SubmitResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.StreamEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public final class RedisQueueStoreContractTest extends QueueStoreContractTest {

    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> redis;
    private static JedisPool pool;
    private static final AtomicInteger counter = new AtomicInteger();

    private QueueStore store;
    private String ns;

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(REDIS_PORT);
        redis.start();
        pool = new JedisPool(redis.getHost(), redis.getMappedPort(REDIS_PORT));
    }

    @AfterAll
    static void stopRedis() {
        if (pool != null) {
            pool.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void freshNamespace() {
        ns = "contract-" + counter.incrementAndGet();
        store = new RedisQueueStore(pool, ns);
    }

    @Override
    protected QueueStore store() {
        return store;
    }

    private String streamKey(Priority p) {
        return "jobqueue:" + ns + ":" + p.name();
    }

    private String group(String groupId) {
        return ns + ":" + groupId;
    }

    @Test
    void closeIsIdempotent() {
        // Own pool on purpose: closing the shared one would poison every later test in this class.
        try (JedisPool localPool = new JedisPool(redis.getHost(), redis.getMappedPort(REDIS_PORT))) {
            RedisQueueStore local = new RedisQueueStore(localPool, "close-" + counter.incrementAndGet());
            local.close();
            local.close();
        }
    }

    @Test
    void duplicateDedupEntryIsAcknowledgedAndLeavesNoPendingEntries() {
        RedisQueueStore rstore = (RedisQueueStore) store();
        rstore.submit(payload("d"), Priority.NORMAL, DedupKey.of("dup-m1"));
        rstore.submit(payload("d"), Priority.NORMAL, DedupKey.of("dup-m1"));
        ClaimedJob first = rstore.claim("g", 1).orElseThrow();
        assertThat(rstore.claim("g", 1)).as("duplicate is collapsed").isEmpty();
        rstore.acknowledge("g", first); // the first entry is legitimately pending until acked
        try (Jedis jedis = pool.getResource()) {
            long pending = jedis.xpending(streamKey(Priority.NORMAL), group("g")).getTotal();
            assertThat(pending).as("skipped duplicate must be XACK'd, not left in the PEL").isZero();
        }
    }

    @Test
    void concurrentPromotionDeliversEachJobExactlyOnce() throws Exception {
        RedisQueueStore rstore = (RedisQueueStore) store();
        Instant past = Instant.now().minusSeconds(60);
        int n = 50;
        for (int i = 0; i < n; i++) {
            rstore.submitDelayed(payload("j" + i), Priority.NORMAL, null, past);
        }
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                tasks.add(() -> rstore.promoteDelayed());
            }
            List<Future<Integer>> futures = exec.invokeAll(tasks);
            for (Future<Integer> f : futures) {
                f.get();
            }
        } finally {
            exec.shutdownNow();
        }
        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.xlen(streamKey(Priority.NORMAL))).as("no double promotion under concurrency").isEqualTo(n);
        }
    }

    @Test
    void reclaimedEntryIsReaddedWithoutInternalFields() {
        RedisQueueStore rstore = (RedisQueueStore) store();
        rstore.submit(payload("x"), Priority.NORMAL, DedupKey.of("dd-reclaim"));
        rstore.claim("g", 1).orElseThrow();
        assertThat(rstore.reclaimPending(1)).isOne();
        // Inspect the stream directly: re-claiming would hit the dedup seen-window and is a separate concern.
        try (Jedis jedis = pool.getResource()) {
            List<StreamEntry> entries = jedis.xrange(streamKey(Priority.NORMAL), "-", "+");
            assertThat(entries).hasSize(2); // original + re-added
            Map<String, String> fields = entries.get(entries.size() - 1).getFields();
            assertThat(fields.keySet()).as("re-added entry must not carry internal fields")
                    .containsExactlyInAnyOrder("payload", "dedup");
        }
    }

    @Test
    void resolveDelayedMapsProvisionalIdToRealEntryAfterPromotion() {
        RedisQueueStore rstore = (RedisQueueStore) store();
        Instant past = Instant.now().minusSeconds(60);
        SubmitResult submitted = rstore.submitDelayed(payload("late"), Priority.NORMAL, null, past);
        assertThat(rstore.resolveDelayed(submitted.jobId())).as("not promoted yet").isEmpty();
        assertThat(rstore.promoteDelayed()).isOne();
        JobId real = rstore.resolveDelayed(submitted.jobId()).orElseThrow();
        try (Jedis jedis = pool.getResource()) {
            List<StreamEntry> entries = jedis.xrange(streamKey(Priority.NORMAL), "-", "+");
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getID().toString()).isEqualTo(real.raw());
        }
    }
}
