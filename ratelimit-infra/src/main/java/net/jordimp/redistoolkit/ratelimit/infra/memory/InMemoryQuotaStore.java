package net.jordimp.redistoolkit.ratelimit.infra.memory;

import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import net.jordimp.redistoolkit.ratelimit.domain.TokenBucketState;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class InMemoryQuotaStore implements QuotaStore {

    private static final int MAX_BUCKETS = 10_000;

    private final Map<String, TokenBucketState> buckets = new LinkedHashMap<String, TokenBucketState>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TokenBucketState> eldest) {
            return size() > MAX_BUCKETS;
        }
    };

    @Override
    public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
        double capacity = spec.burst();
        double rate = spec.tokensPerSecond();
        String rendered = key.render();

        Decision[] result = new Decision[1];
        synchronized (buckets) {
            TokenBucketState current = buckets.get(rendered);
            TokenBucketState state = (current == null)
                    ? new TokenBucketState(capacity, now)
                    : current.refilled(now, rate, capacity);
            if (state.canConsume(1)) {
                TokenBucketState consumed = state.consume(1);
                buckets.put(rendered, consumed);
                result[0] = Decision.ok((long) Math.floor(consumed.tokens()), spec.limit());
            } else {
                buckets.put(rendered, state);
                long waitSeconds = secondsUntilNextToken(state.tokens(), rate);
                result[0] = Decision.rejected(Reason.LIMIT_EXCEEDED, spec.limit(), Duration.ofSeconds(waitSeconds));
            }
        }
        return result[0];
    }

    private static long secondsUntilNextToken(double tokens, double rate) {
        double deficit = 1.0 - tokens;
        return Math.max(1L, (long) Math.ceil(deficit / rate));
    }
}
