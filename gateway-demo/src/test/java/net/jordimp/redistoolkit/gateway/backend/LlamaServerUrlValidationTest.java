package net.jordimp.redistoolkit.gateway.backend;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlamaServerUrlValidationTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void acceptsAbsoluteHttpsUrlWithHost() {
        assertThatCode(() -> new LlamaServerBackend(URI.create("https://llama.example.com:8443/v1"), client, json))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://llama.example.com/v1",
            "/v1/completions",
            "https:///v1/completions"
    })
    void rejectsUrlThatIsNotAbsoluteHttpsWithHost(String rawUri) {
        URI uri = URI.create(rawUri);
        assertThatThrownBy(() -> new LlamaServerBackend(uri, client, json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejectsNullBaseUrl() {
        assertThatThrownBy(() -> new LlamaServerBackend(null, client, json))
                .isInstanceOf(NullPointerException.class);
    }
}
