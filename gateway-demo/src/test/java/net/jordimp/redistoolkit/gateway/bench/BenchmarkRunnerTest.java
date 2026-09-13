package net.jordimp.redistoolkit.gateway.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.time.Instant;
import java.util.List;
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
        Clock clock = Instant::now;
        RateLimiterService service = new RateLimiterService(clock, new InMemoryQuotaStore());
        KeyExtractor extractor = new KeyExtractor();
        RateLimitRegistry registry = new RateLimitRegistry(Map.of("/v1/completions", RateLimitSpec.perMinute(10_000)));
        DecisionMapper mapper = new DecisionMapper();
        GatewayApp app = GatewayApp.builder()
                .service(service)
                .extractor(extractor)
                .registry(registry)
                .mapper(mapper)
                .backend(new StubBackend())
                .json(new ObjectMapper())
                .build();
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

    @Test
    void percentile_clampsIndexIntoBounds() {
        List<Double> values = List.of(10.0, 20.0, 30.0, 40.0, 50.0);

        assertThat(BenchmarkRunner.percentile(values, 0.0)).isEqualTo(10.0);
        assertThat(BenchmarkRunner.percentile(values, 0.5)).isEqualTo(30.0);
        assertThat(BenchmarkRunner.percentile(values, 0.95)).isEqualTo(50.0);
        assertThat(BenchmarkRunner.percentile(values, 1.0)).isEqualTo(50.0);
    }

    @Test
    void percentile_clampsLowQuantileToFirstElement() {
        List<Double> values = List.of(1.0, 2.0, 3.0);

        assertThat(BenchmarkRunner.percentile(values, -1.0)).isEqualTo(1.0);
    }
}
