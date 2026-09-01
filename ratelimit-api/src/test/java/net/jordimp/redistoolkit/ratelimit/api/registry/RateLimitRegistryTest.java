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
    void r4_returnsRegisteredSpec() {
        Optional<RateLimitSpec> found = registry.find("/v1/completions");

        assertThat(found).contains(spec);
    }

    @Test
    void r5_signalsAbsenceWhenUnregistered() {
        assertThat(registry.find("/unknown")).isEmpty();
    }

    @Test
    void r5_nullRouteSignalsAbsence() {
        assertThat(registry.find(null)).isEmpty();
    }
}
