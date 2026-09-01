package net.jordimp.redistoolkit.gateway.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReadmeSectionsTest {

    private static final Path README = Path.of("..", "README.md");

    @Test
    void readme_containsAllRequiredHeadings() throws IOException {
        assertThat(README).as("missing %s", README.toAbsolutePath()).exists();
        String content = Files.readString(README);

        for (String title : new String[] {"Quickstart", "Usage", "Architecture", "Benchmark", "Trade-offs"}) {
            assertThat(hasHeading(content, title))
                    .as("missing required heading '%s' in %s", title, README.toAbsolutePath())
                    .isTrue();
        }
    }

    private static boolean hasHeading(String content, String title) {
        return content.lines().anyMatch(line -> line.startsWith("#") && line.contains(title));
    }
}
