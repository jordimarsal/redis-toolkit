package net.jordimp.redistoolkit.ratelimit.api;

import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;

public final class KeyExtractor {

    public QuotaKey extract(Dimension dimension, String value) {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("quota key value must be non-blank, got \"" + value + "\"");
        }
        return new QuotaKey(value, dimension);
    }
}
