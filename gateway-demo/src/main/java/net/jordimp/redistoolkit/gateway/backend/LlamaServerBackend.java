package net.jordimp.redistoolkit.gateway.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class LlamaServerBackend implements InferenceBackend {

    private static final String PATH = "/v1/completions";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final URI baseUrl;
    private final HttpClient client;
    private final ObjectMapper json;

    public LlamaServerBackend(URI baseUrl, HttpClient client, ObjectMapper json) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
    }

    private static boolean isValidLlamaBaseUrl(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return "https".equalsIgnoreCase(scheme) && host != null && !host.isEmpty();
    }

    @Override
    public Completion complete(CompletionRequest request) {
        URI targetUri;
        try {
            targetUri = baseUrl.resolve(PATH);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid base URL: " + baseUrl, e);
        }

        if (!isValidLlamaBaseUrl(baseUrl)) {
            throw new IllegalArgumentException("LLM_BASE_URL must be an absolute https:// URL");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(targetUri)
                .header("Content-Type", "application/json")
                .timeout(RESPONSE_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request)))
                .build();

        try {
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling llama-server", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call llama-server at " + baseUrl, e);
        }
    }

    String requestBody(CompletionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("prompt", request.prompt());
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize completion request", e);
        }
    }

    Completion parseResponse(String body) {
        try {
            JsonNode root = json.readTree(body);
            String id = root.path("id").asText("llama-1");
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                return new Completion(id, choices.get(0).path("text").asText(""));
            }
            String text = root.path("completion").asText(root.path("content").asText(""));
            return new Completion(id, text);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid llama-server response: " + body, e);
        }
    }
}
