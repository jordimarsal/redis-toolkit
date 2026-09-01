package net.jordimp.redistoolkit.ratelimit.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.port.Clock;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimiterServiceTest {

    private final Instant fixed = Instant.parse("2026-08-20T12:00:00Z");

    private static final class FixedClock implements Clock {
        private final Instant instant;

        FixedClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant now() {
            return instant;
        }
    }

    private static final class RecordingStore implements QuotaStore {
        private QuotaKey key;
        private RateLimitSpec spec;
        private Instant now;
        private int calls;
        @Override
        public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
            this.key = key;
            this.spec = spec;
            this.now = now;
            this.calls++;
            return Decision.ok(7, 10);
        }
    }

    @Test
    void r15_r16_delegatesWithInjectedTimeAndReturnsDecisionUnchanged() {
        RecordingStore store = new RecordingStore();
        RateLimiterService service = new RateLimiterService(new FixedClock(fixed), store);
        QuotaKey key = new QuotaKey("acme", Dimension.TENANT);
        RateLimitSpec spec = RateLimitSpec.perMinute(10);

        Decision result = service.evaluate(key, spec);

        assertThat(store.calls).isEqualTo(1);
        assertThat(store.key).isEqualTo(key);
        assertThat(store.spec).isEqualTo(spec);
        assertThat(store.now).isEqualTo(fixed);
        assertThat(result).isEqualTo(Decision.ok(7, 10));
    }
}
