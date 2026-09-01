package net.jordimp.redistoolkit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.client.CollectorRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jordimp.redistoolkit.gateway.backend.StubBackend;
import net.jordimp.redistoolkit.ratelimit.api.KeyExtractor;
import net.jordimp.redistoolkit.ratelimit.api.mapper.DecisionMapper;
import net.jordimp.redistoolkit.ratelimit.api.registry.RateLimitRegistry;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.infra.memory.InMemoryQuotaStore;
import net.jordimp.redistoolkit.ratelimit.port.Clock;
import net.jordimp.redistoolkit.ratelimit.usecase.RateLimiterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayShutdownTest {

    private static final String ROUTE = "/v1/completions";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private GatewayApp app;

    @AfterEach
    void stopGateway() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void stop_closesInjectedResource() {
        AtomicBoolean closed = new AtomicBoolean(false);
        Clock clock = () -> T0;
        RateLimiterService service = new RateLimiterService(clock, new InMemoryQuotaStore());
        KeyExtractor extractor = new KeyExtractor();
        RateLimitRegistry registry = new RateLimitRegistry(Map.of(ROUTE, RateLimitSpec.perMinute(5)));
        DecisionMapper mapper = new DecisionMapper();
        ObjectMapper json = new ObjectMapper();
        CollectorRegistry metrics = new CollectorRegistry();
        app = new GatewayApp(service, extractor, registry, mapper, new StubBackend(), json, metrics, () -> closed.set(true));

        app.start(0);
        assertThat(closed.get()).isFalse();
        app.stop();

        assertThat(closed.get()).isTrue();
    }
}
