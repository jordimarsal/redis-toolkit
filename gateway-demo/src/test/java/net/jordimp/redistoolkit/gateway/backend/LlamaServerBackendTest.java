package net.jordimp.redistoolkit.gateway.backend;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class LlamaServerBackendTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void requestBody_producesOpenAiCompatiblePayload() {
        LlamaServerBackend backend = new LlamaServerBackend(URI.create("https://llama.local/v1"), HttpClient.newHttpClient(), json);
        String body = backend.requestBody(new CompletionRequest("my-model", "say hi"));
        assertThat(body).contains("\"model\":\"my-model\"").contains("\"prompt\":\"say hi\"");
    }

    @Test
    void parseResponse_extractsGeneratedText_fromChoices() {
        LlamaServerBackend backend = new LlamaServerBackend(URI.create("https://llama.local/v1"), HttpClient.newHttpClient(), json);
        Completion completion = backend.parseResponse("{\"id\":\"abc\",\"choices\":[{\"text\":\"hello world\"}]}");
        assertThat(completion.text()).isEqualTo("hello world");
        assertThat(completion.id()).isEqualTo("abc");
    }

    @Test
    void parseResponse_extractsGeneratedText_fromCompletionField() {
        LlamaServerBackend backend = new LlamaServerBackend(URI.create("https://llama.local/v1"), HttpClient.newHttpClient(), json);
        Completion completion = backend.parseResponse("{\"completion\":\"fallback text\"}");
        assertThat(completion.text()).isEqualTo("fallback text");
    }

    @Test
    void parseResponse_truncatesHugeInvalidBodyInErrorMessage() {
        LlamaServerBackend backend = new LlamaServerBackend(URI.create("https://llama.local/v1"), HttpClient.newHttpClient(), json);
        String huge = "{" + "x".repeat(10_000);
        assertThatThrownBy(() -> backend.parseResponse(huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truncated")
                .satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(600));
    }

    @Test
    void backends_areInterchangeable_behindPort() {
        InferenceBackend stub = new StubBackend();
        InferenceBackend llama = new LlamaServerBackend(URI.create("https://llama.local/v1"), HttpClient.newHttpClient(), json);
        assertThat(stub.complete(new CompletionRequest("stub", "hi")).text()).isNotBlank();
        assertThat(llama).isInstanceOf(InferenceBackend.class);
    }
}
