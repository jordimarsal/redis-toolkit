package net.jordimp.redistoolkit.ratelimit.infra.memory;

import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import net.jordimp.redistoolkit.ratelimit.domain.TokenBucketState;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryQuotaStore implements QuotaStore {

    private final ConcurrentHashMap<String, TokenBucketState> buckets = new ConcurrentHashMap<>();

    @Override
    public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
        double capacity = spec.burst();
        double rate = spec.tokensPerSecond();
        String rendered = key.render();

        Decision[] result = new Decision[1];
        buckets.compute(rendered, (k, current) -> {
            TokenBucketState state = (current == null)
                    ? new TokenBucketState(capacity, now)
                    : current.refilled(now, rate, capacity);
            if (state.canConsume(1)) {
                TokenBucketState consumed = state.consume(1);
                result[0] = Decision.ok((long) Math.floor(consumed.tokens()), spec.limit());
                return consumed;
            }
            long waitSeconds = secondsUntilNextToken(state.tokens(), rate);
            result[0] = Decision.rejected(Reason.LIMIT_EXCEEDED, spec.limit(), Duration.ofSeconds(waitSeconds));
            return state;
        });
        return result[0];
    }

    private static long secondsUntilNextToken(double tokens, double rate) {
        double deficit = 1.0 - tokens;
        return Math.max(1L, (long) Math.ceil(deficit / rate));
    }
}
