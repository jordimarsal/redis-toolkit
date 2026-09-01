package net.jordimp.redistoolkit.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitSpecTest {

    @Test
    void r01_rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> RateLimitSpec.of(0, Duration.ofMinutes(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitSpec(-5, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void r02_exposesImmutableValuesAndValueEquality() {
        RateLimitSpec a = new RateLimitSpec(10, Duration.ofSeconds(30), 20);
        RateLimitSpec b = new RateLimitSpec(10, Duration.ofSeconds(30), 20);
        assertThat(a.limit()).isEqualTo(10);
        assertThat(a.refillWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(a.burst()).isEqualTo(20);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void r03_perMinuteFactory() {
        RateLimitSpec p = RateLimitSpec.perMinute(42);
        assertThat(p.limit()).isEqualTo(42);
        assertThat(p.refillWindow()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.burst()).isEqualTo(42);
    }
}
