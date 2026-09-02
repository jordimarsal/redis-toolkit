package net.jordimp.redistoolkit.gateway.backend;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class LlamaServerUrlValidationTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void accepts_absoluteHttpsUrlWithHost() {
        assertThatCode(() -> new LlamaServerBackend(URI.create("https://llama.example.com:8443/v1"), client, json))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_httpScheme() {
        assertThatThrownBy(() -> new LlamaServerBackend(URI.create("http://llama.example.com/v1"), client, json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejects_relativeUri() {
        assertThatThrownBy(() -> new LlamaServerBackend(URI.create("/v1/completions"), client, json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejects_httpsWithoutHost() {
        assertThatThrownBy(() -> new LlamaServerBackend(URI.create("https://"), client, json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejects_nullBaseUrl() {
        assertThatThrownBy(() -> new LlamaServerBackend(null, client, json))
                .isInstanceOf(NullPointerException.class);
    }
}
