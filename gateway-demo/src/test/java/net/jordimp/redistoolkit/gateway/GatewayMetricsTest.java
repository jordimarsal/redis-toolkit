package net.jordimp.redistoolkit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.prometheus.client.CollectorRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import net.jordimp.redistoolkit.gateway.backend.StubBackend;
import net.jordimp.redistoolkit.ratelimit.api.KeyExtractor;
import net.jordimp.redistoolkit.ratelimit.api.mapper.DecisionMapper;
import net.jordimp.redistoolkit.ratelimit.api.registry.RateLimitRegistry;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.infra.memory.InMemoryQuotaStore;
import net.jordimp.redistoolkit.ratelimit.infra.resilience.FailurePolicy;
import net.jordimp.redistoolkit.ratelimit.infra.resilience.ResilientQuotaStore;
import net.jordimp.redistoolkit.ratelimit.port.Clock;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import net.jordimp.redistoolkit.ratelimit.usecase.RateLimiterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class GatewayMetricsTest {

    private static final String ROUTE = "/v1/completions";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Javalin javalin;
    private int port;
    private HttpClient http;

    @BeforeEach
    void startGateway() {
        Clock clock = () -> T0;
        RateLimiterService service = new RateLimiterService(clock, new InMemoryQuotaStore());
        KeyExtractor extractor = new KeyExtractor();
        RateLimitRegistry registry = new RateLimitRegistry(Map.of(ROUTE, RateLimitSpec.perMinute(1)));
        DecisionMapper mapper = new DecisionMapper();
        ObjectMapper json = new ObjectMapper();
        CollectorRegistry metrics = new CollectorRegistry();
        GatewayApp app = new GatewayApp(service, extractor, registry, mapper, new StubBackend(), json, metrics, null);
        javalin = app.start(0);
        port = app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopGateway() {
        if (javalin != null) {
            javalin.stop();
        }
    }

    @Test
    void metricsEndpoint_exposesDecisionCounters_afterAllowedAndDeniedRequests() throws Exception {
        assertThat(post(port, "{\"model\":\"stub\",\"prompt\":\"a\"}").statusCode()).isEqualTo(200);
        assertThat(post(port, "{\"model\":\"stub\",\"prompt\":\"b\"}").statusCode()).isEqualTo(429);

        HttpResponse<String> resp = getMetrics(port);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(sampleValue(resp.body(), "ratelimit_decisions_total", "allowed")).isEqualTo(1.0);
        assertThat(sampleValue(resp.body(), "ratelimit_decisions_total", "denied")).isEqualTo(1.0);
    }

    @Test
    void metricsEndpoint_exposesStoreFailureAndDegradedMetrics_whenPrimaryFails() throws Exception {
        CollectorRegistry registry = new CollectorRegistry();
        ResilientQuotaStore store = new ResilientQuotaStore(failingPrimary(), new InMemoryQuotaStore(), FailurePolicy.DEGRADED_LOCAL, registry);
        Clock clock = () -> T0;
        RateLimiterService service = new RateLimiterService(clock, store);
        KeyExtractor extractor = new KeyExtractor();
        RateLimitRegistry limits = new RateLimitRegistry(Map.of(ROUTE, RateLimitSpec.perMinute(5)));
        DecisionMapper mapper = new DecisionMapper();
        ObjectMapper json = new ObjectMapper();
        GatewayApp app = new GatewayApp(service, extractor, limits, mapper, new StubBackend(), json, registry, null);
        Javalin local = app.start(0);
        try {
            int localPort = app.port();
            assertThat(post(localPort, "{\"model\":\"stub\",\"prompt\":\"a\"}").statusCode()).isEqualTo(200);

            HttpResponse<String> resp = getMetrics(localPort);

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(sampleValue(resp.body(), "ratelimit_decisions_total", "allowed")).isEqualTo(1.0);
            assertThat(sampleValue(resp.body(), "ratelimit_store_failures_total")).isEqualTo(1.0);
            assertThat(sampleValue(resp.body(), "ratelimit_degraded")).isEqualTo(1.0);
        } finally {
            local.stop();
        }
    }

    @Test
    void metricsEndpoint_rejectsProxiedRequests_evenWhenTcpPeerIsLoopback() throws Exception {
        // Behind a reverse proxy every external client arrives from loopback; forwarding headers
        // mark such requests so the loopback check cannot be bypassed by topology alone.
        HttpResponse<String> viaXff = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/metrics"))
                .header("X-Forwarded-For", "203.0.113.7")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(viaXff.statusCode()).isEqualTo(403);

        HttpResponse<String> viaRealIp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/metrics"))
                .header("X-Real-Ip", "203.0.113.8")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(viaRealIp.statusCode()).isEqualTo(403);
    }

    private static QuotaStore failingPrimary() {
        return (key, spec, now) -> {
            throw new JedisConnectionException("simulated redis outage");
        };
    }

    private static Double sampleValue(String body, String metric, String result) {
        for (String line : body.split("\n")) {
            boolean isSample = !line.isBlank() && !line.startsWith("#")
                    && line.startsWith(metric + "{")
                    && line.contains("result=\"" + result + "\"");
            if (isSample) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return null;
    }

    private static Double sampleValue(String body, String metric) {
        for (String line : body.split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")
                    && (line.startsWith(metric + " ") || line.startsWith(metric + "{"))) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return null;
    }

    private HttpResponse<String> post(int port, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + ROUTE))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getMetrics(int port) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/metrics"))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
