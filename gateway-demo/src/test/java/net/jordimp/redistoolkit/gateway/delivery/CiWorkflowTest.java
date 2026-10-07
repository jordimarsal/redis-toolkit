package net.jordimp.redistoolkit.gateway.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CiWorkflowTest {

    private static final Path CI = Path.of("..", ".github", "workflows", "ci.yml");

    @SuppressWarnings("unchecked")
    private Map<String, Object> workflow() throws IOException {
        assertThat(CI).as(".github/workflows/ci.yml al root del repo").exists();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        return yaml.readValue(CI.toFile(), Map.class);
    }

    @Test
    void runsMvnTestOnPushAndPullRequestWithConcurrencyGroup() throws Exception {
        Map<String, Object> doc = workflow();

        Map<String, Object> on = asMap(doc.get("on"));
        assertThat(on).containsKeys("push", "pull_request");

        Map<String, Object> concurrency = asMap(doc.get("concurrency"));
        assertThat(concurrency).containsEntry("cancel-in-progress", true);
        assertThat(String.valueOf(concurrency.get("group"))).contains("github.ref");

        Map<String, Object> jobs = asMap(doc.get("jobs"));
        assertThat(jobs).containsKeys("test", "style", "compose-smoke");

        List<Object> steps = asList(asMap(jobs.get("test")).get("steps"));
        String allSteps = steps.stream()
                .map(s -> String.valueOf(asMap(s).getOrDefault("run", "")))
                .collect(Collectors.joining("\n"));
        // CI runs the real build/tests directly; it must not depend on the harness (init.sh).
        assertThat(allSteps).contains("mvn test").doesNotContain("./init.sh");

        List<Object> styleSteps = asList(asMap(jobs.get("style")).get("steps"));
        String styleRun = styleSteps.stream()
                .map(s -> String.valueOf(asMap(s).getOrDefault("run", "")))
                .collect(Collectors.joining("\n"));
        assertThat(styleRun).contains("checkstyle:check");
    }

    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }
}
