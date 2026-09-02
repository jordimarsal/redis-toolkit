package net.jordimp.redistoolkit.jobqueue.domain;

import java.util.Objects;

/**
 * Caller-supplied idempotency key. Validated at the boundary because it is embedded in Redis stream
 * member strings and hash fields: only printable ASCII without '|' is allowed, so a hostile value can
 * never corrupt the encoding or inject extra fields.
 */
public final class DedupKey {

    private static final int MAX_LENGTH = 128;

    private final String raw;

    private DedupKey(String raw) {
        this.raw = raw;
    }

    public static DedupKey of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.isBlank()) {
            throw new IllegalArgumentException("dedup key length must be between 1 and " + MAX_LENGTH + ", got blank value");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("dedup key exceeds maximum length of " + MAX_LENGTH + ", got " + value.length());
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '|') {
                throw new IllegalArgumentException("dedup key must not contain the '|' character");
            }
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("dedup key must not contain control characters or non-ASCII bytes");
            }
        }
        return new DedupKey(value);
    }

    public String raw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DedupKey other)) {
            return false;
        }
        return raw.equals(other.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(raw);
    }

    @Override
    public String toString() {
        return "DedupKey[" + raw + "]";
    }
}
