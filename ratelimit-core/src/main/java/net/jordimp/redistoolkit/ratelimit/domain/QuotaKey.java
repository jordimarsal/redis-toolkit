package net.jordimp.redistoolkit.ratelimit.domain;

import java.util.Locale;

public record QuotaKey(String value, Dimension dimension) {

    /** Hard cap on key material so client-controlled values cannot inflate store keys unboundedly. */
    public static final int MAX_VALUE_LENGTH = 128;

    public QuotaKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must be non-blank");
        }
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "value exceeds maximum length of " + MAX_VALUE_LENGTH + ", got " + value.length());
        }
        if (!value.chars().allMatch(c -> c >= 0x20 && c != 0x7F)) {
            throw new IllegalArgumentException("value must not contain control characters");
        }
    }

    public String render() {
        return "ratelimit:" + dimension.name().toLowerCase(Locale.ROOT) + ":" + value;
    }

    public QuotaKey withDimension(Dimension other) {
        return new QuotaKey(value, other);
    }
}
