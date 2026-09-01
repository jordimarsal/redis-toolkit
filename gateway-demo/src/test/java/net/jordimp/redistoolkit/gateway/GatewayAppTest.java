package net.jordimp.redistoolkit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jordimp.redistoolkit.gateway.backend.StubBackend;
import net.jordimp.redistoolkit.ratelimit.api.KeyExtractor;
import net.jordimp.redistoolkit.ratelimit.api.mapper.DecisionMapper;
import net.jordimp.redistoolkit.ratelimit.api.registry.RateLimitRegistry;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.infra.memory.InMemoryQuotaStore;
import net.jordimp.redistoolkit.ratelimit.port.Clock;
import net.jordimp.redistoolkit.ratelimit.usecase.RateLimiterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayAppTest {

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
        RateLimitRegistry registry = new RateLimitRegistry(Map.of(ROUTE, RateLimitSpec.perMinute(3)));
        DecisionMapper mapper = new DecisionMapper();
        ObjectMapper json = new ObjectMapper();
        GatewayApp app = new GatewayApp(service, extractor, registry, mapper, new StubBackend(), json);
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
    void underLimit_returns200_completionAndHeaders() throws Exception {
        HttpResponse<String> resp = post("{\"model\":\"stub\",\"prompt\":\"hello\"}");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("X-RateLimit-Limit")).contains("3");
        assertThat(resp.headers().firstValue("X-RateLimit-Remaining")).isPresent();
        assertThat(resp.headers().firstValue("X-RateLimit-Reset")).isPresent();
        assertThat(resp.body()).contains("Stub completion for").contains("hello");
    }

    @Test
    void overLimit_returns429_withPositiveRetryAfter() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> resp = post("{\"model\":\"stub\",\"prompt\":\"x\"}");
            statuses.add(resp.statusCode());
            if (resp.statusCode() == 429) {
                String retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
                assertThat(retryAfter).as("Retry-After on response #%d", i).isNotNull();
                assertThat(Long.parseLong(retryAfter)).isGreaterThan(0L);
                assertThat(resp.headers().firstValue("X-RateLimit-Limit")).contains("3");
            }
        }
        long allowed = statuses.stream().filter(s -> s == 200).count();
        long denied = statuses.stream().filter(s -> s == 429).count();
        assertThat(allowed).isEqualTo(3L);
        assertThat(denied).isEqualTo(2L);
    }

    @Test
    void invalidJsonBody_returns400_badRequest() throws Exception {
        HttpResponse<String> resp = post("{this is not valid json");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("bad_request");
    }

    @Test
    void emptyBody_usesDefaultRequest_andServesStub() throws Exception {
        HttpResponse<String> resp = post("");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("Stub completion for");
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + ROUTE))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
