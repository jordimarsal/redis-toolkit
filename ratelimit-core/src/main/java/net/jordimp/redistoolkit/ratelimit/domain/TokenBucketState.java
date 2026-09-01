package net.jordimp.redistoolkit.ratelimit.domain;

import java.time.Duration;
import java.time.Instant;

public record TokenBucketState(double tokens, Instant lastRefill) {

    public boolean canConsume(int amount) {
        return amount >= 0 && tokens >= amount;
    }

    public TokenBucketState consume(int amount) {
        if (!canConsume(amount)) {
            return this;
        }
        return new TokenBucketState(tokens - amount, lastRefill);
    }

    public TokenBucketState refilled(Instant now, double ratePerSec, double capacity) {
        if (now == null || lastRefill == null) {
            throw new IllegalArgumentException("instants must not be null");
        }
        double elapsedSeconds = Math.max(0.0, Duration.between(lastRefill, now).toNanos() / 1_000_000_000.0);
        double next = Math.min(capacity, tokens + elapsedSeconds * ratePerSec);
        return new TokenBucketState(next, now);
    }
}
