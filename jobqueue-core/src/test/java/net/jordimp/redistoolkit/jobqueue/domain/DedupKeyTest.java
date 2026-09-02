package net.jordimp.redistoolkit.jobqueue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DedupKeyTest {

    @Test
    void of_acceptsPrintableAsciiWithoutPipe() {
        DedupKey key = DedupKey.of("order-42");
        assertThat(key).isNotNull();
    }

    @Test
    void raw_returnsTheOriginalValue() {
        assertThat(DedupKey.of("abc-123").raw()).isEqualTo("abc-123");
    }

    @Test
    void of_rejectsNull() {
        assertThatThrownBy(() -> DedupKey.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_rejectsEmptyAndBlank() {
        assertThatThrownBy(() -> DedupKey.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
        assertThatThrownBy(() -> DedupKey.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsLongerThan128Chars() {
        String tooLong = "k".repeat(129);
        assertThatThrownBy(() -> DedupKey.of(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128")
                .hasMessageContaining("129");
    }

    @Test
    void of_acceptsExactly128Chars() {
        assertThat(DedupKey.of("k".repeat(128))).isNotNull();
    }

    @Test
    void of_rejectsPipeCharacter() {
        assertThatThrownBy(() -> DedupKey.of("a|b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("|");
    }

    @Test
    void of_rejectsControlCharacters() {
        assertThatThrownBy(() -> DedupKey.of("a\nb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control");
        assertThatThrownBy(() -> DedupKey.of("a\tb"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsNonAsciiBytes() {
        assertThatThrownBy(() -> DedupKey.of("café"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        assertThat(DedupKey.of("same")).isEqualTo(DedupKey.of("same"));
        assertThat(DedupKey.of("same").hashCode()).isEqualTo(DedupKey.of("same").hashCode());
        assertThat(DedupKey.of("one")).isNotEqualTo(DedupKey.of("two"));
        assertThat(DedupKey.of("x")).isNotEqualTo("x");
    }
}
