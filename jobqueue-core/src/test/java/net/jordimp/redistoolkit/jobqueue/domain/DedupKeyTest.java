package net.jordimp.redistoolkit.jobqueue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DedupKeyTest {

    @Test
    void ofAcceptsPrintableAsciiWithoutPipe() {
        DedupKey key = DedupKey.of("order-42");
        assertThat(key).isNotNull();
    }

    @Test
    void rawReturnsTheOriginalValue() {
        assertThat(DedupKey.of("abc-123").raw()).isEqualTo("abc-123");
    }

    @Test
    void ofRejectsNull() {
        assertThatThrownBy(() -> DedupKey.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofRejectsEmptyAndBlank() {
        assertThatThrownBy(() -> DedupKey.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
        assertThatThrownBy(() -> DedupKey.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofRejectsLongerThan128Chars() {
        String tooLong = "k".repeat(129);
        assertThatThrownBy(() -> DedupKey.of(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128")
                .hasMessageContaining("129");
    }

    @Test
    void ofAcceptsExactly128Chars() {
        assertThat(DedupKey.of("k".repeat(128))).isNotNull();
    }

    @Test
    void ofRejectsPipeCharacter() {
        assertThatThrownBy(() -> DedupKey.of("a|b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("|");
    }

    @Test
    void ofRejectsControlCharacters() {
        assertThatThrownBy(() -> DedupKey.of("a\nb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control");
        assertThatThrownBy(() -> DedupKey.of("a\tb"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofRejectsNonAsciiBytes() {
        assertThatThrownBy(() -> DedupKey.of("café"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        assertThat(DedupKey.of("same")).isEqualTo(DedupKey.of("same"));
        assertThat(DedupKey.of("same")).hasSameHashCodeAs(DedupKey.of("same"));
        assertThat(DedupKey.of("one")).isNotEqualTo(DedupKey.of("two"));
        assertThat(DedupKey.of("x")).isNotEqualTo("x");
    }
}
