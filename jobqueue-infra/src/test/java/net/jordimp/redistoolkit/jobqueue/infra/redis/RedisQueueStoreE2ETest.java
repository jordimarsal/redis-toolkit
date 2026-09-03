package net.jordimp.redistoolkit.jobqueue.infra.redis;

import net.jordimp.redistoolkit.jobqueue.infra.e2e.JobQueueE2EContract;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end path through WorkerLoop against a real Redis, spun up with Testcontainers so the whole
 * queue behaves exactly as it would in production (consumer groups, XACK, delayed promotion) without any
 * manual infrastructure. Runs every scenario from {@link JobQueueE2EContract} on top of Redis.
 */
public final class RedisQueueStoreE2ETest extends JobQueueE2EContract {

    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> redis;
    private static JedisPool pool;
    private static final AtomicInteger counter = new AtomicInteger();

    @BeforeAll
    static void startRedis() {
        // Pinned by digest so the image cannot drift under us, matching docker-compose.yml.
        redis = new GenericContainer<>(
                DockerImageName.parse("redis:7@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7"))
                        .withExposedPorts(REDIS_PORT);
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

    @Override
    protected QueueStore createStore() {
        return new RedisQueueStore(pool, "e2e-" + counter.incrementAndGet());
    }
}
