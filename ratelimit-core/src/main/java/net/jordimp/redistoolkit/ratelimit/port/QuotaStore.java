package net.jordimp.redistoolkit.ratelimit.port;

import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import java.time.Instant;

public interface QuotaStore {
    Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now);
}
