package net.jordimp.redistoolkit.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.client.CollectorRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.jordimp.redistoolkit.gateway.backend.InferenceBackend;
import net.jordimp.redistoolkit.gateway.backend.LlamaServerBackend;
import net.jordimp.redistoolkit.gateway.backend.StubBackend;
import net.jordimp.redistoolkit.ratelimit.api.KeyExtractor;
import net.jordimp.redistoolkit.ratelimit.api.mapper.DecisionMapper;
import net.jordimp.redistoolkit.ratelimit.api.registry.RateLimitRegistry;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.infra.memory.InMemoryQuotaStore;
import net.jordimp.redistoolkit.ratelimit.infra.redis.RedisQuotaStore;
import net.jordimp.redistoolkit.ratelimit.infra.resilience.FailurePolicy;
import net.jordimp.redistoolkit.ratelimit.infra.resilience.ResilientQuotaStore;
import net.jordimp.redistoolkit.ratelimit.port.Clock;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import net.jordimp.redistoolkit.ratelimit.usecase.RateLimiterService;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public final class Main {

    private static final String ROUTE = "/v1/completions";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_LIMIT_PER_MINUTE = 60;

    private static final int REDIS_SOCKET_TIMEOUT_MS = 10_000;
    private static final int REDIS_CONNECT_TIMEOUT_MS = 10_000;
    private static final long REDIS_MIN_EVICTABLE_IDLE_MS = 300_000L; // 5 minutes
    private static final long REDIS_EVICTION_RUN_INTERVAL_MS = 30_000L; // evictor sweep every 30 s
    private static final long REDIS_MAX_WAIT_MS = 5_000L; // fail fast when the pool is exhausted

    record StoreWiring(QuotaStore store, AutoCloseable resource) {
    }

    public static void main(String[] args) {
        Clock clock = () -> Instant.now();
        CollectorRegistry metrics = CollectorRegistry.defaultRegistry;
        StoreWiring wiring = createStoreWiring(System.getenv("REDIS_HOST"), parseRedisPort(), metrics);
        RateLimiterService service = new RateLimiterService(clock, wiring.store());
        KeyExtractor extractor = new KeyExtractor();
        RateLimitRegistry registry = new RateLimitRegistry(Map.of(ROUTE, RateLimitSpec.perMinute(parseLimitPerMinute())));
        DecisionMapper mapper = new DecisionMapper();
        ObjectMapper json = new ObjectMapper();

        InferenceBackend backend = createBackend(System.getenv("BACKEND"), System.getenv("LLM_BASE_URL"));
        GatewayApp app = new GatewayApp(service, extractor, registry, mapper, backend, json, metrics, wiring.resource());
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        app.start(parsePort(args));
        System.out.println("gateway-demo listening on http://localhost:" + app.port() + "  (POST /v1/completions)");
    }

    static StoreWiring createStoreWiring(String redisHost, int redisPort, CollectorRegistry metrics) {
        if (redisHost == null || redisHost.isBlank()) {
            return new StoreWiring(new InMemoryQuotaStore(), null);
        }
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setMinEvictableIdleTimeMillis(REDIS_MIN_EVICTABLE_IDLE_MS);
        poolConfig.setTimeBetweenEvictionRunsMillis(REDIS_EVICTION_RUN_INTERVAL_MS);
        poolConfig.setMaxWait(Duration.ofMillis(REDIS_MAX_WAIT_MS));
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(REDIS_CONNECT_TIMEOUT_MS)
                .socketTimeoutMillis(REDIS_SOCKET_TIMEOUT_MS)
                .build();
        JedisPool pool = new JedisPool(poolConfig, new HostAndPort(redisHost, redisPort), clientConfig);
        RedisQuotaStore primary = new RedisQuotaStore(pool);
        ResilientQuotaStore resilient = new ResilientQuotaStore(primary, new InMemoryQuotaStore(), FailurePolicy.DEGRADED_LOCAL, metrics);
        return new StoreWiring(resilient, resilient);
    }

    // Only BACKEND=llama activates the real llama-server client; any other value (or blank) falls back to the
    // in-repo StubBackend so the gateway always runs offline. Wire another backend explicitly here if needed.
    static InferenceBackend createBackend(String type, String baseUrl) {
        if ("llama".equalsIgnoreCase(type)) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("BACKEND=llama requires LLM_BASE_URL");
            }
            URI uri;
            try {
                uri = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("LLM_BASE_URL is not a valid URI: " + baseUrl, e);
            }
            return new LlamaServerBackend(uri, HttpClient.newHttpClient(), new ObjectMapper());
        }
        return new StubBackend();
    }

    private static int parseRedisPort() {
        String raw = System.getenv("REDIS_PORT");
        if (raw != null && !raw.isBlank()) {
            try {
                int port = Integer.parseInt(raw.trim());
                if (port < 1 || port > 65_535) {
                    throw new IllegalArgumentException("REDIS_PORT out of range 1..65535, got " + port);
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("REDIS_PORT must be an integer in 1..65535, got \"" + raw + "\"", e);
            }
        }
        return DEFAULT_REDIS_PORT;
    }

    private static int parseLimitPerMinute() {
        String raw = System.getenv("LIMIT_PER_MINUTE");
        if (raw != null && !raw.isBlank()) {
            try {
                int n = Integer.parseInt(raw.trim());
                if (n < 1) {
                    throw new IllegalArgumentException("LIMIT_PER_MINUTE must be >= 1, got " + n);
                }
                return n;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("LIMIT_PER_MINUTE must be a positive integer, got \"" + raw + "\"", e);
            }
        }
        return DEFAULT_LIMIT_PER_MINUTE;
    }

    private static int parsePort(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            try {
                int port = Integer.parseInt(args[0].trim());
                if (port < 1 || port > 65_535) {
                    throw new IllegalArgumentException("port out of range 1..65535, got " + port);
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("port must be an integer in 1..65535, got \"" + args[0] + "\"", e);
            }
        }
        String env = System.getenv("GATEWAY_PORT");
        if (env != null && !env.isBlank()) {
            try {
                int port = Integer.parseInt(env.trim());
                if (port < 1 || port > 65_535) {
                    throw new IllegalArgumentException("GATEWAY_PORT out of range 1..65535, got " + port);
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("GATEWAY_PORT must be an integer in 1..65535, got \"" + env + "\"", e);
            }
        }
        return DEFAULT_PORT;
    }
}
