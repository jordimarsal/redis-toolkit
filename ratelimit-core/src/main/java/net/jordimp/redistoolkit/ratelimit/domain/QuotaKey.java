package net.jordimp.redistoolkit.ratelimit.domain;

import java.util.Locale;

public record QuotaKey(String value, Dimension dimension) {

    public QuotaKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must be non-blank");
        }
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
    }

    public String render() {
        return "ratelimit:" + dimension.name().toLowerCase(Locale.ROOT) + ":" + value;
    }

    public QuotaKey withDimension(Dimension other) {
        return new QuotaKey(value, other);
    }
}
