package net.jordimp.redistoolkit.jobqueue.domain;

public record ClaimedJob(JobId jobId, Payload payload, String dedupKey, int attemptNumber) {

    public ClaimedJob withAttempt(int newAttempt) {
        return new ClaimedJob(jobId, payload, dedupKey, newAttempt);
    }
}
