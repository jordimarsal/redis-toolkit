package net.jordimp.redistoolkit.ratelimit.infra.redis;

import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.infra.contract.QuotaStoreContractTest;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class RedisQuotaStoreContractTest extends QuotaStoreContractTest {

    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> redis;
    private static JedisPool pool;

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

    @Override
    protected QuotaStore store() {
        return new RedisQuotaStore(pool);
    }

    @Test
    void r5_multi_replicas_share_one_global_budget() {
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        RateLimitSpec spec = RateLimitSpec.of(10, Duration.ofMinutes(1), 10); // 10 tokens totals
        QuotaKey k = new QuotaKey("shared-user", Dimension.TENANT);

        RedisQuotaStore replica1 = new RedisQuotaStore(pool, "shared-budget");
        RedisQuotaStore replica2 = new RedisQuotaStore(pool, "shared-budget");
        RedisQuotaStore replica3 = new RedisQuotaStore(pool, "shared-budget");

        int admitted = 0;
        for (int i = 0; i < 15; i++) {
            QuotaStore replica = switch (i % 3) {
                case 0 -> replica1;
                case 1 -> replica2;
                default -> replica3;
            };
            if (replica.evaluateAndConsume(k, spec, t0).allowed()) {
                admitted++;
            }
        }

        assertThat(admitted).isEqualTo(10);
    }
}
