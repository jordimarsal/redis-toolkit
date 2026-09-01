package net.jordimp.redistoolkit.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.client.CollectorRegistry;
import java.net.URI;
import java.net.http.HttpClient;
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
import redis.clients.jedis.JedisPool;

public final class Main {

    private static final String ROUTE = "/v1/completions";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final int DEFAULT_LIMIT_PER_MINUTE = 60;

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
        JedisPool pool = new JedisPool(redisHost, redisPort);
        RedisQuotaStore primary = new RedisQuotaStore(pool);
        ResilientQuotaStore resilient = new ResilientQuotaStore(primary, new InMemoryQuotaStore(), FailurePolicy.DEGRADED_LOCAL, metrics);
        return new StoreWiring(resilient, resilient);
    }

    static InferenceBackend createBackend(String type, String baseUrl) {
        if ("llama".equalsIgnoreCase(type)) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("BACKEND=llama requires LLM_BASE_URL");
            }
            return new LlamaServerBackend(URI.create(baseUrl), HttpClient.newHttpClient(), new ObjectMapper());
        }
        return new StubBackend();
    }

    private static int parseRedisPort() {
        String env = System.getenv("REDIS_PORT");
        if (env != null && !env.isBlank()) {
            return Integer.parseInt(env.trim());
        }
        return DEFAULT_REDIS_PORT;
    }

    private static int parseLimitPerMinute() {
        String env = System.getenv("LIMIT_PER_MINUTE");
        if (env != null && !env.isBlank()) {
            return Integer.parseInt(env.trim());
        }
        return DEFAULT_LIMIT_PER_MINUTE;
    }

    private static int parsePort(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Integer.parseInt(args[0]);
        }
        String env = System.getenv("GATEWAY_PORT");
        if (env != null && !env.isBlank()) {
            return Integer.parseInt(env.trim());
        }
        return 8080;
    }
}
