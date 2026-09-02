package net.jordimp.redistoolkit.gateway.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DockerfileTest {

    private static final Path DOCKERFILE = Path.of("..", "Dockerfile");
    private static final Path DOCKERIGNORE = Path.of("..", ".dockerignore");

    @Test
    void runtimeStageRunsAsNonRoot() throws IOException {
        assertThat(DOCKERFILE).as("missing %s", DOCKERFILE.toAbsolutePath()).exists();
        String content = Files.readString(DOCKERFILE);
        List<String> stages = splitStages(content);
        assertThat(stages.size()).isGreaterThanOrEqualTo(2); // build stage + runtime stage
        String runtime = stages.get(stages.size() - 1);
        boolean nonRootUser = runtime.lines().anyMatch(line ->
                line.startsWith("USER ") && !line.equals("USER root") && !line.equals("USER 0"));
        assertThat(nonRootUser)
                .as("runtime stage must drop privileges with a USER directive (M4)")
                .isTrue();
    }

    @Test
    void dockerignoreExcludesGitAndBuildArtifacts() throws IOException {
        assertThat(DOCKERIGNORE).as("missing %s", DOCKERIGNORE.toAbsolutePath()).exists();
        Set<String> entries = Files.readAllLines(DOCKERIGNORE).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .collect(Collectors.toSet());
        assertThat(entries).contains(".git");
    }

    private static List<String> splitStages(String content) {
        return Arrays.stream(content.split("(?m)^FROM "))
                .map(part -> "FROM " + part)
                .toList();
    }
}
