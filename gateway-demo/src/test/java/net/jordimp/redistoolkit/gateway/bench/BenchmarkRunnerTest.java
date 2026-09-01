package net.jordimp.redistoolkit.gateway.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.time.Instant;
import java.util.Map;
import net.jordimp.redistoolkit.gateway.GatewayApp;
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

class BenchmarkRunnerTest {

    private Javalin javalin;
    private int port;

    @BeforeEach
    void startGateway() {
        Clock clock = () -> Instant.now();
        RateLimiterService service = new RateLimiterService(clock, new InMemoryQuotaStore());
        KeyExtractor extractor = new KeyExtractor();
        RateLimitRegistry registry = new RateLimitRegistry(Map.of("/v1/completions", RateLimitSpec.perMinute(10_000)));
        DecisionMapper mapper = new DecisionMapper();
        GatewayApp app = new GatewayApp(service, extractor, registry, mapper, new StubBackend(), new ObjectMapper());
        javalin = app.start(0);
        port = app.port();
    }

    @AfterEach
    void stopGateway() {
        if (javalin != null) {
            javalin.stop();
        }
    }

    @Test
    void run_reportsAllOkAndMachineReadableSummary_whenGatewayServesUnderLimit() throws Exception {
        BenchmarkRunner.Summary summary = BenchmarkRunner.run("http://localhost:" + port, 200);

        assertThat(summary.ok()).isEqualTo(200);
        assertThat(summary.failed()).isZero();
        assertThat(summary.meanMs()).isGreaterThan(0.0);
        assertThat(summary.p95Ms()).isGreaterThanOrEqualTo(0.0);
        assertThat(summary.rps()).isGreaterThan(0.0);
        assertThat(summary.render())
                .matches("ok=200 failed=0 mean_ms=[\\d.]+ p95_ms=[\\d.]+ p99_ms=[\\d.]+ rps=[\\d.]+");
    }

    @Test
    void run_throwsIllegalState_whenGatewayUnreachable() {
        assertThatThrownBy(() -> BenchmarkRunner.run("http://localhost:1", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }
}
