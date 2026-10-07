package net.jordimp.redistoolkit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prometheus.client.CollectorRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import net.jordimp.redistoolkit.gateway.backend.InferenceBackend;
import net.jordimp.redistoolkit.gateway.backend.LlamaServerBackend;
import net.jordimp.redistoolkit.gateway.backend.StubBackend;
import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.infra.memory.InMemoryQuotaStore;
import net.jordimp.redistoolkit.ratelimit.infra.resilience.ResilientQuotaStore;
import org.junit.jupiter.api.Test;

class MainWiringTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final QuotaKey KEY = new QuotaKey("localhost", Dimension.IP);

    @Test
    void withoutRedisHostUsesPlainInMemoryStoreAndNoResource() {
        Main.StoreWiring wiring = Main.createStoreWiring(null, 6379, new CollectorRegistry());

        assertThat(wiring.store()).isInstanceOf(InMemoryQuotaStore.class);
        assertThat(wiring.resource()).isNull();
    }

    @Test
    void blankRedisHostUsesPlainInMemoryStoreAndNoResource() {
        Main.StoreWiring wiring = Main.createStoreWiring("   ", 6379, new CollectorRegistry());

        assertThat(wiring.store()).isInstanceOf(InMemoryQuotaStore.class);
        assertThat(wiring.resource()).isNull();
    }

    @Test
    void withRedisHostWrapsResilientStoreServingFromLocalFallbackWhenRedisDown() throws Exception {
        int closedPort = firstClosedPort();
        CollectorRegistry registry = new CollectorRegistry();
        Main.StoreWiring wiring = Main.createStoreWiring("localhost", closedPort, registry);
        try {
            Decision decision = wiring.store().evaluateAndConsume(KEY, RateLimitSpec.perMinute(5), NOW);

            assertThat(wiring.store()).isInstanceOf(ResilientQuotaStore.class);
            assertThat(decision.isAllowed()).isTrue();
            assertThat(registry.getSampleValue("ratelimit_store_failures_total")).isEqualTo(1.0);
            assertThat(registry.getSampleValue("ratelimit_degraded")).isEqualTo(1.0);
        } finally {
            if (wiring.resource() != null) {
                wiring.resource().close();
            }
        }
    }

    @Test
    void createBackendDefaultsToStubWhenTypeNullBlankOrUnknown() {
        assertThat(Main.createBackend(null, null)).isInstanceOf(StubBackend.class);
        assertThat(Main.createBackend("   ", "http://ignored")).isInstanceOf(StubBackend.class);
        assertThat(Main.createBackend("stub", "http://ignored")).isInstanceOf(StubBackend.class);
    }

    @Test
    void createBackendReturnsLlamaBackendWhenTypeIsLlamaWithHttpsUrl() {
        InferenceBackend backend = Main.createBackend("llama", "https://llama-tls:8443/v1");

        assertThat(backend).isInstanceOf(LlamaServerBackend.class);
    }

    @Test
    void createBackendThrowsIllegalArgumentWhenLlamaWithoutBaseUrl() {
        assertThatThrownBy(() -> Main.createBackend("llama", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM_BASE_URL");
        assertThatThrownBy(() -> Main.createBackend("llama", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM_BASE_URL");
    }

    @Test
    void createBackendThrowsIllegalArgumentWhenLlamaUrlIsNotHttps() {
        assertThatThrownBy(() -> Main.createBackend("llama", "http://localhost:8080/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void createBackendThrowsIllegalArgumentWhenLlamaUrlIsMalformed() {
        assertThatThrownBy(() -> Main.createBackend("llama", "ht!tp://bad url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM_BASE_URL");
    }

    private static int firstClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
