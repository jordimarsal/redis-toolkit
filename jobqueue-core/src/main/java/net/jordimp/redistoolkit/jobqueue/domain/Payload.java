package net.jordimp.redistoolkit.jobqueue.domain;

import java.util.Arrays;
import java.util.Objects;

public final class Payload {

    private static final long MAX_PAYLOAD_BYTES = 1L << 20;

    private final byte[] data;

    private Payload(byte[] data) {
        this.data = Arrays.copyOf(data, data.length);
    }

    public static Payload of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload exceeds max size of " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return new Payload(bytes);
    }

    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Payload other)) {
            return false;
        }
        return Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(data);
    }

    @Override
    public String toString() {
        return "Payload[" + data.length + " bytes]";
    }
}
