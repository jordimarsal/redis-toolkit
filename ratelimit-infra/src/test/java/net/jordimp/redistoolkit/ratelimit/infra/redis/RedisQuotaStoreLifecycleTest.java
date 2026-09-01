package net.jordimp.redistoolkit.ratelimit.infra.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

class RedisQuotaStoreLifecycleTest {

    @Test
    void close_closesUnderlyingJedisPool() {
        JedisPool pool = new JedisPool("localhost", 6379);
        RedisQuotaStore store = new RedisQuotaStore(pool);
        store.close();
        assertThatThrownBy(() -> pool.getResource())
                .isInstanceOf(JedisException.class)
                .rootCause().isInstanceOf(IllegalStateException.class);
    }
}
