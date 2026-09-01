package net.jordimp.redistoolkit.ratelimit.domain;

import java.time.Duration;

public record RateLimitSpec(int limit, Duration refillWindow, int burst) {

    public RateLimitSpec {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, got " + limit);
        }
        if (refillWindow == null || refillWindow.isZero() || refillWindow.isNegative()) {
            throw new IllegalArgumentException("refillWindow must be positive");
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be >= 1, got " + burst);
        }
    }

    public static RateLimitSpec of(int limit, Duration refillWindow, int burst) {
        return new RateLimitSpec(limit, refillWindow, burst);
    }

    public static RateLimitSpec perMinute(int n) {
        return new RateLimitSpec(n, Duration.ofSeconds(60), n);
    }

    public double tokensPerSecond() {
        double seconds = refillWindow.toNanos() / 1_000_000_000.0;
        return limit / seconds;
    }
}
