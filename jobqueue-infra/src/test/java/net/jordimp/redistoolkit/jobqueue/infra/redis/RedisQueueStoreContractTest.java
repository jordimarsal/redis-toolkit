package net.jordimp.redistoolkit.jobqueue.infra.redis;

import net.jordimp.redistoolkit.jobqueue.contract.QueueStoreContractTest;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.atomic.AtomicInteger;

public final class RedisQueueStoreContractTest extends QueueStoreContractTest {

    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> redis;
    private static JedisPool pool;
    private static final AtomicInteger counter = new AtomicInteger();

    private QueueStore store;

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
        store = new RedisQueueStore(pool, "contract-" + counter.incrementAndGet());
    }

    @Override
    protected QueueStore store() {
        return store;
    }

    @Test
    void closeIsIdempotent() {
        store.close();
        store.close();
    }
}
