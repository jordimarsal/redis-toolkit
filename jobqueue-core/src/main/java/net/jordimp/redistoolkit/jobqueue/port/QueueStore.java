package net.jordimp.redistoolkit.jobqueue.port;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.DedupKey;
import net.jordimp.redistoolkit.jobqueue.domain.JobId;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;

import java.time.Instant;

import java.util.Optional;

public interface QueueStore extends AutoCloseable {

    /** {@code dedupKey} may be null (no idempotency) or a validated key that collapses repeated delivery. */
    SubmitResult submit(Payload payload, Priority priority, DedupKey dedupKey);

    SubmitResult submitDelayed(Payload payload, Priority priority, DedupKey dedupKey, Instant runAt);

    Optional<ClaimedJob> claim(String groupId, int maxPoll);

    void acknowledge(String groupId, ClaimedJob claimed);

    int reclaimPending(int maxClaim);

    PendingStats pendingStats();

    int promoteDelayed();

    /**
     * Resolves a provisional id returned by {@link #submitDelayed} to the real stream entry id once the job has
     * been promoted. Default is empty (store does not support resolution); Redis overrides it via a delay map.
     */
    default Optional<JobId> resolveDelayed(JobId provisional) {
        return Optional.empty();
    }

    @Override
    void close();
}
