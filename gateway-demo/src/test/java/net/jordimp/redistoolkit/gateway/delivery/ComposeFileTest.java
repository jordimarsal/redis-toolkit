package net.jordimp.redistoolkit.gateway.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposeFileTest {

    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");

    @SuppressWarnings("unchecked")
    private Map<String, Object> compose() throws IOException {
        assertThat(COMPOSE).as("docker-compose.yml al root del repo").exists();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        return yaml.readValue(COMPOSE.toFile(), Map.class);
    }

    @Test
    void definesRedisAndGatewayServices_withRequiredWiring() throws Exception {
        Map<String, Object> doc = compose();
        Map<String, Object> services = asMap(doc.get("services"));

        assertThat(services.keySet())
                .containsExactlyInAnyOrder("redis", "gateway", "llama-server", "llama-tls");

        Map<String, Object> redis = asMap(services.get("redis"));
        // Supply-chain hardening: images must be pinned by digest, not just by tag.
        assertThat(String.valueOf(redis.get("image"))).startsWith("redis:7@sha256:");
        assertThat(asMap(redis.get("healthcheck"))).isNotEmpty();

        Map<String, Object> gateway = asMap(services.get("gateway"));
        assertThat(gateway.get("build")).isEqualTo(".");
        assertThat(asList(gateway.get("ports"))).contains("8080:8080");
        assertThat(asMap(gateway.get("depends_on"))).containsKey("redis");

        Map<String, Object> env = asMap(gateway.get("environment"));
        // LLM_TRUSTSTORE must be plumbable so operators can point the gateway at their CA bundle (M5).
        assertThat(env).containsKeys("REDIS_HOST", "BACKEND", "LLM_BASE_URL", "LLM_TRUSTSTORE");
        assertThat(String.valueOf(env.get("REDIS_HOST"))).isEqualTo("redis");
        // The gateway enforces https:// for LLM_BASE_URL; compose must not ship a plaintext URL.
        assertThat(String.valueOf(env.get("LLM_BASE_URL"))).startsWith("https://");
    }

    @Test
    void llamaTlsTerminatorIsOptInProfile() throws Exception {
        Map<String, Object> doc = compose();
        Map<String, Object> services = asMap(doc.get("services"));
        Map<String, Object> tls = asMap(services.get("llama-tls"));

        assertThat(asList(tls.get("profiles"))).containsExactly("llama");
        assertThat(asList(tls.get("depends_on"))).containsExactly("llama-server");
    }

    @Test
    void llamaServerIsOptInProfile() throws Exception {
        Map<String, Object> doc = compose();
        Map<String, Object> services = asMap(doc.get("services"));
        Map<String, Object> llama = asMap(services.get("llama-server"));

        assertThat(asList(llama.get("profiles"))).containsExactly("llama");
        assertThat(String.valueOf(llama.get("image"))).contains("llama.cpp");
    }

    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }
}
