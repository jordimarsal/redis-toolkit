package net.jordimp.redistoolkit.ratelimit.infra.redis;

import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class RedisQuotaStore implements QuotaStore, AutoCloseable {

    private static final String LUA_TOKEN_BUCKET = """
            local key      = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate     = tonumber(ARGV[2])
            local now_ms   = tonumber(ARGV[3])
            local ttl_ms   = tonumber(ARGV[4])

            local data   = redis.call('HMGET', key, 'tokens', 'last')
            local tokens = tonumber(data[1])
            if tokens == nil then tokens = capacity end
            local last   = tonumber(data[2])
            if last == nil then last = now_ms end

            if now_ms > last then
              local elapsed_s = (now_ms - last) / 1000.0
              tokens = math.min(capacity, tokens + elapsed_s * rate)
            end

            local allowed = 0
            local remaining = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
              remaining = math.floor(tokens)
            else
              local deficit = 1.0 - tokens
              if rate and rate > 0 then
                retry = math.ceil(deficit / rate)
              else
                retry = 1
              end
              if retry < 1 then retry = 1 end
            end

            redis.call('HSET', key, 'tokens', tostring(tokens), 'last', tostring(now_ms))
            redis.call('PEXPIRE', key, ttl_ms)
            return {allowed, remaining, retry}
            """;

    private final JedisPool pool;
    private final String keyPrefix;

    public RedisQuotaStore(JedisPool pool) {
        this(pool, "inst-" + UUID.randomUUID());
    }

    public RedisQuotaStore(JedisPool pool, String keyPrefix) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
        List<String> keys = List.of(keyPrefix + ":" + key.render());
        List<String> args = List.of(
                Integer.toString(spec.burst()),
                Double.toString(spec.tokensPerSecond()),
                Long.toString(now.toEpochMilli()),
                Long.toString(ttlMillisFor(spec)));

        Object raw;
        try (Jedis jedis = pool.getResource()) {
            raw = jedis.eval(LUA_TOKEN_BUCKET, keys, args);
        }

        if (!(raw instanceof List<?> rows) || rows.size() < 3) {
            throw new IllegalStateException("Unexpected response from token-bucket script: " + raw);
        }

        long allowed   = toLong(rows.get(0));
        long remaining = toLong(rows.get(1));
        long retrySecs = toLong(rows.get(2));

        if (allowed == 1L) {
            return Decision.ok(remaining, spec.limit());
        }
        return Decision.rejected(Reason.LIMIT_EXCEEDED, spec.limit(), Duration.ofSeconds(retrySecs));
    }

    private long ttlMillisFor(RateLimitSpec spec) {
        return spec.refillWindow().toMillis() + 60_000L;
    }

    @Override
    public void close() {
        pool.close();
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = value.toString().trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Math.round(Double.parseDouble(s));
        }
    }
}
