package net.jordimp.redistoolkit.ratelimit.domain;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record Decision(boolean allowed, long remaining, long limit, Duration retryAfter, Reason reason) {

    public boolean isAllowed() {
        return allowed;
    }

    public Long retryAfterSeconds() {
        if (retryAfter == null) {
            return null;
        }
        return Math.max(0L, retryAfter.getSeconds());
    }

    /**
     * Renders rate-limit response headers. Note: {@code X-RateLimit-Reset} carries seconds until
     * the next token becomes available (0 while allowed), not an epoch timestamp; {@code Retry-After}
     * mirrors it and is only present when positive.
     */
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-RateLimit-Limit", String.valueOf(limit));
        h.put("X-RateLimit-Remaining", String.valueOf(Math.max(0L, remaining)));
        long resetSecs = retryAfter == null ? 0L : Math.max(0L, retryAfter.getSeconds());
        h.put("X-RateLimit-Reset", String.valueOf(resetSecs));
        Long ra = retryAfterSeconds();
        if (ra != null && ra > 0) {
            h.put("Retry-After", String.valueOf(ra));
        }
        return h;
    }

    public static Decision ok(long remaining, long limit) {
        return new Decision(true, remaining, limit, null, Reason.OK);
    }

    public static Decision rejected(Reason reason, long limit, Duration retryAfter) {
        return new Decision(false, 0L, limit, retryAfter, reason);
    }
}
