package net.jordimp.redistoolkit.jobqueue.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadTest {

    @Test
    void returnsADefensiveCopyThatStaysUnchanged() {
        byte[] bytes = {1, 2, 3};
        Payload payload = Payload.of(bytes);
        bytes[0] = 99;
        assertThat(payload.data()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }

    @Test
    void dataAccessReturnsSameBytesWithoutExposingInternalBuffer() {
        Payload payload = Payload.of(new byte[]{9, 8, 7});
        byte[] first = payload.data();
        first[1] = 0;
        assertThat(payload.data()).containsExactly((byte) 9, (byte) 8, (byte) 7);
    }
}
