package net.jordimp.redistoolkit.jobqueue.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimedJobTest {

    private final Payload payload = Payload.of(new byte[]{1});
    private final JobId jobId = JobId.of("j1");

    @Test
    void exposesAllConstructionValues() {
        ClaimedJob job = new ClaimedJob(jobId, payload, "dedup-1", 2);
        assertThat(job.jobId()).isEqualTo(jobId);
        assertThat(job.payload()).isEqualTo(payload);
        assertThat(job.dedupKey()).isEqualTo("dedup-1");
        assertThat(job.attemptNumber()).isEqualTo(2);
    }

    @Test
    void withAttemptReturnsNewInstanceWithoutMutating() {
        ClaimedJob original = new ClaimedJob(jobId, payload, "dedup-1", 1);
        ClaimedJob bumped = original.withAttempt(3);
        assertThat(bumped.attemptNumber()).isEqualTo(3);
        assertThat(original.attemptNumber()).isEqualTo(1);
    }
}
