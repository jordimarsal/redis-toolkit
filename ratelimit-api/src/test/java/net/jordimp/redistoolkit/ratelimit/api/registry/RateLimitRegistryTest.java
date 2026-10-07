package net.jordimp.redistoolkit.ratelimit.api.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import org.junit.jupiter.api.Test;

class RateLimitRegistryTest {

    private final RateLimitSpec spec = RateLimitSpec.perMinute(10);
    private final RateLimitRegistry registry = new RateLimitRegistry(Map.of("/v1/completions", spec));

    @Test
    void r4ReturnsRegisteredSpec() {
        Optional<RateLimitSpec> found = registry.find("/v1/completions");

        assertThat(found).contains(spec);
    }

    @Test
    void r5SignalsAbsenceWhenUnregistered() {
        assertThat(registry.find("/unknown")).isEmpty();
    }

    @Test
    void r5NullRouteSignalsAbsence() {
        assertThat(registry.find(null)).isEmpty();
    }
}
