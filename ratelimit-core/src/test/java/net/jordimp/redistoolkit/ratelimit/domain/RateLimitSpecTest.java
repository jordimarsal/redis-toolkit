package net.jordimp.redistoolkit.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitSpecTest {

    @Test
    void r01RejectsNonPositiveLimit() {
        Duration oneMinute = Duration.ofMinutes(1);
        assertThatThrownBy(() -> RateLimitSpec.of(0, oneMinute, 1))
                .isInstanceOf(IllegalArgumentException.class);
        Duration thirtySeconds = Duration.ofSeconds(30);
        assertThatThrownBy(() -> new RateLimitSpec(-5, thirtySeconds, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void r02ExposesImmutableValuesAndValueEquality() {
        RateLimitSpec a = new RateLimitSpec(10, Duration.ofSeconds(30), 20);
        RateLimitSpec b = new RateLimitSpec(10, Duration.ofSeconds(30), 20);
        assertThat(a.limit()).isEqualTo(10);
        assertThat(a.refillWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(a.burst()).isEqualTo(20);
        assertThat(a)
                .isEqualTo(b)
                .hasSameHashCodeAs(b);
    }

    @Test
    void r03PerMinuteFactory() {
        RateLimitSpec p = RateLimitSpec.perMinute(42);
        assertThat(p.limit()).isEqualTo(42);
        assertThat(p.refillWindow()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.burst()).isEqualTo(42);
    }
}
