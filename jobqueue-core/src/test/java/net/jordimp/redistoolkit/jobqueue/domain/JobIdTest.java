package net.jordimp.redistoolkit.jobqueue.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobIdTest {

    @Test
    void rejectsNullRawId() {
        assertThatThrownBy(() -> new JobId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankRawId() {
        assertThatThrownBy(() -> new JobId("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsTheProvidedRawId() {
        JobId id = JobId.of("abc-123");
        assertThat(id.raw()).isEqualTo("abc-123");
    }

    @Test
    void twoIdsWithSameRawAreEqual() {
        assertThat(JobId.of("same")).isEqualTo(JobId.of("same"));
        assertThat(JobId.of("same")).hasSameHashCodeAs(JobId.of("same"));
    }
}
