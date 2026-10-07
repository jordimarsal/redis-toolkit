package net.jordimp.redistoolkit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jordimp.redistoolkit.gateway.backend.InferenceBackend;
import net.jordimp.redistoolkit.gateway.backend.StubBackend;
import org.junit.jupiter.api.Test;

class MainCreateBackendTest {

    @Test
    void createBackendStubIsDefaultWhenTypeNotLlama() {
        InferenceBackend backend = Main.createBackend("stub", null);
        assertThat(backend).isInstanceOf(StubBackend.class);
    }

    @Test
    void createBackendLlamaWithoutTrustStoreUsesDefaultTls() {
        InferenceBackend backend = Main.createBackend("llama", "https://llama-tls:8443/v1");
        assertThat(backend).isNotNull();
    }

    @Test
    void createBackendRejectsMissingTrustStoreFileFailFast() {
        assertThatThrownBy(() -> Main.createBackend("llama", "https://llama-tls:8443/v1", "/nonexistent/ca.p12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM_TRUSTSTORE");
    }
}
