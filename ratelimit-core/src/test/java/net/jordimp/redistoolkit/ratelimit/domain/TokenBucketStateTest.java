package net.jordimp.redistoolkit.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TokenBucketStateTest {

    private final Instant t0 = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void r12_refillAddsElapsedRateCappedAtCapacity() {
        TokenBucketState s = new TokenBucketState(5.0, t0);
        TokenBucketState after = s.refilled(t0.plusSeconds(10), 1.0, 10.0);
        assertThat(after.tokens()).isEqualTo(10.0);
        assertThat(after.lastRefill()).isEqualTo(t0.plusSeconds(10));

        TokenBucketState belowCap =
                new TokenBucketState(5.0, t0).refilled(t0.plusSeconds(2), 1.0, 100.0);
        assertThat(belowCap.tokens()).isEqualTo(7.0);
    }

    @Test
    void r13_canConsumeReflectsAvailability() {
        TokenBucketState s = new TokenBucketState(3.0, t0);
        assertThat(s.canConsume(3)).isTrue();
        assertThat(s.canConsume(4)).isFalse();
    }

    @Test
    void r14_consumeLeavesUnchangedWhenInsufficient() {
        TokenBucketState s = new TokenBucketState(2.0, t0);
        TokenBucketState unchanged = s.consume(5);
        assertThat(unchanged.tokens()).isEqualTo(2.0);
        assertThat(unchanged.lastRefill()).isEqualTo(t0);
    }
}
