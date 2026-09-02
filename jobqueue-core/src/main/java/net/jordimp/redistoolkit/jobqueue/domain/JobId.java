package net.jordimp.redistoolkit.jobqueue.domain;

import java.util.UUID;
import java.util.Objects;

public final class JobId {

    private final String raw;

    public JobId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("JobId must not be blank");
        }
        this.raw = raw;
    }

    public static JobId of(String raw) {
        return new JobId(raw);
    }

    public static JobId generate() {
        return new JobId(UUID.randomUUID().toString());
    }

    public String raw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobId other)) {
            return false;
        }
        return Objects.equals(raw, other.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(raw);
    }

    @Override
    public String toString() {
        return raw;
    }
}
