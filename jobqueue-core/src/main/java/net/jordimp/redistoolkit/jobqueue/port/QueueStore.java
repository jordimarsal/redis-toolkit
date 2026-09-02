package net.jordimp.redistoolkit.jobqueue.port;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;

import java.time.Instant;

import java.util.Optional;

public interface QueueStore extends AutoCloseable {

    SubmitResult submit(Payload payload, Priority priority, String dedupKey);

    SubmitResult submitDelayed(Payload payload, Priority priority, String dedupKey, Instant runAt);

    Optional<ClaimedJob> claim(String groupId, int maxPoll);

    void acknowledge(String groupId, ClaimedJob claimed);

    int reclaimPending(int maxClaim);

    PendingStats pendingStats();

    int promoteDelayed();

    @Override
    void close();
}
