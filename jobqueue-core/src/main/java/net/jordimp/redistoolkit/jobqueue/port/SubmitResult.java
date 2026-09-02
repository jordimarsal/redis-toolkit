package net.jordimp.redistoolkit.jobqueue.port;

import net.jordimp.redistoolkit.jobqueue.domain.JobId;

public record SubmitResult(String queueName, JobId jobId) {
}
