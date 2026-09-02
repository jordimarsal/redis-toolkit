package net.jordimp.redistoolkit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jordimp.redistoolkit.gateway.backend.InferenceBackend;
import net.jordimp.redistoolkit.gateway.backend.StubBackend;
import org.junit.jupiter.api.Test;

class MainCreateBackendTest {

    @Test
    void createBackend_stubIsDefault_whenTypeNotLlama() {
        InferenceBackend backend = Main.createBackend("stub", null);
        assertThat(backend).isInstanceOf(StubBackend.class);
    }

    @Test
    void createBackend_llamaWithoutTrustStore_usesDefaultTls() {
        InferenceBackend backend = Main.createBackend("llama", "https://llama-tls:8443/v1");
        assertThat(backend).isNotNull();
    }

    @Test
    void createBackend_rejectsMissingTrustStoreFile_failFast() {
        assertThatThrownBy(() -> Main.createBackend("llama", "https://llama-tls:8443/v1", "/nonexistent/ca.p12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM_TRUSTSTORE");
    }
}
