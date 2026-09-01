package net.jordimp.redistoolkit.ratelimit.usecase;

import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.port.Clock;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import java.time.Instant;

public final class RateLimiterService {

    private final Clock clock;
    private final QuotaStore store;

    public RateLimiterService(Clock clock, QuotaStore store) {
        if (clock == null || store == null) {
            throw new IllegalArgumentException("clock and store must not be null");
        }
        this.clock = clock;
        this.store = store;
    }

    public Decision evaluate(QuotaKey key, RateLimitSpec spec) {
        Instant now = clock.now();
        return store.evaluateAndConsume(key, spec, now);
    }
}
