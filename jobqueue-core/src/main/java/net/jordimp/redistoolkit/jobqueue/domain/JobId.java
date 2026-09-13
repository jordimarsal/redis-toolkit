package net.jordimp.redistoolkit.jobqueue.domain;

import java.util.UUID;

public record JobId(String raw) {

    public JobId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("JobId must not be blank");
        }
    }

    public static JobId of(String raw) {
        return new JobId(raw);
    }

    public static JobId generate() {
        return new JobId(UUID.randomUUID().toString());
    }
}
