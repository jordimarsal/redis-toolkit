package net.jordimp.redistoolkit.jobqueue.support;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.DedupKey;
import net.jordimp.redistoolkit.jobqueue.domain.JobId;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.port.PendingStats;
import net.jordimp.redistoolkit.jobqueue.port.SubmitResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FakeQueueStore implements QueueStore {

    private static final class Enqueued {
        final Payload payload;
        final DedupKey dedupKey;
        final Priority priority;

        Enqueued(Payload payload, DedupKey dedupKey, Priority priority) {
            this.payload = payload;
            this.dedupKey = dedupKey;
            this.priority = priority;
        }
    }

    private final Map<Priority, Queue<Enqueued>> inbox = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ClaimedJob>> pendingByGroup = new ConcurrentHashMap<>();
    private final TreeMap<Long, Enqueued> delayed = new TreeMap<>();

    public FakeQueueStore() {
        for (Priority p : Priority.values()) {
            inbox.put(p, new java.util.concurrent.LinkedBlockingDeque<>());
        }
    }

    @Override
    public SubmitResult submit(Payload payload, Priority priority, DedupKey dedupKey) {
        JobId id = JobId.generate();
        inbox.get(priority).add(new Enqueued(payload, dedupKey, priority));
        return new SubmitResult("fake", id);
    }

    @Override
    public SubmitResult submitDelayed(Payload payload, Priority priority, DedupKey dedupKey, Instant runAt) {
        JobId id = JobId.generate();
        delayed.put(runAt.toEpochMilli(), new Enqueued(payload, dedupKey, priority));
        return new SubmitResult("fake", id);
    }

    @Override
    public synchronized Optional<ClaimedJob> claim(String groupId, int maxPoll) {
        for (Priority p : new Priority[]{Priority.HIGH, Priority.NORMAL, Priority.LOW}) {
            Queue<Enqueued> q = inbox.get(p);
            Enqueued next = null;
            while (next == null && !q.isEmpty()) {
                Enqueued candidate = q.peek();
                if (candidate != null) {
                    next = candidate;
                    q.poll();
                }
            }
            if (next != null) {
                ClaimedJob claimed = new ClaimedJob(JobId.generate(), next.payload, next.dedupKey == null ? null : next.dedupKey.raw(), 1);
                pendingByGroup.computeIfAbsent(groupId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(claimed);
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    @Override
    public void acknowledge(String groupId, ClaimedJob claimed) {
        List<ClaimedJob> list = pendingByGroup.get(groupId);
        if (list != null) {
            list.removeIf(c -> c.jobId().equals(claimed.jobId()));
        }
    }

    @Override
    public synchronized int reclaimPending(int maxClaim) {
        return 0;
    }

    @Override
    public synchronized int promoteDelayed() {
        long now = System.currentTimeMillis();
        int promoted = 0;
        java.util.Map.Entry<Long, Enqueued> entry;
        while ((entry = delayed.pollFirstEntry()) != null && entry.getKey() <= now) {
            inbox.get(entry.getValue().priority).add(entry.getValue());
            promoted++;
        }
        return promoted;
    }

    @Override
    public PendingStats pendingStats() {
        long total = pendingByGroup.values().stream().mapToInt(java.util.List::size).sum();
        return new PendingStats(total, total);
    }

    @Override
    public void close() {
    }
}
