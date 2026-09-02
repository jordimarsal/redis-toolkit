package net.jordimp.redistoolkit.jobqueue.infra.memory;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.JobId;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.port.PendingStats;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.port.SubmitResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryQueueStore implements QueueStore {

    private static final long SEEN_TTL_MS = 60_000L;

    private record Stored(JobId jobId, Payload payload, String dedupKey, Priority priority) {
    }

    private final String queueName;
    private final Map<Priority, List<Stored>> allJobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Stored>> pendingByGroup = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<JobId>> ackedByGroup = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> seen = new ConcurrentHashMap<>();
    private final TreeMap<Long, Stored> delayed = new TreeMap<>();

    public InMemoryQueueStore(String queueName) {
        this.queueName = (queueName == null || queueName.isBlank()) ? "default" : queueName;
        for (Priority p : Priority.values()) {
            allJobs.put(p, new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public synchronized SubmitResult submit(Payload payload, Priority priority, String dedupKey) {
        Stored stored = new Stored(JobId.generate(), payload, dedupKey, priority);
        allJobs.get(priority).add(stored);
        return new SubmitResult(queueName, stored.jobId());
    }

    @Override
    public synchronized SubmitResult submitDelayed(Payload payload, Priority priority, String dedupKey, Instant runAt) {
        Stored stored = new Stored(JobId.generate(), payload, dedupKey, priority);
        delayed.put(runAt.toEpochMilli(), stored);
        return new SubmitResult(queueName, stored.jobId());
    }

    @Override
    public synchronized Optional<ClaimedJob> claim(String groupId, int maxPoll) {
        List<Stored> currentPending = pendingByGroup.computeIfAbsent(groupId, k -> new java.util.ArrayList<>());
        int delivered = 0;
        outer:
        for (Priority p : new Priority[]{Priority.HIGH, Priority.NORMAL, Priority.LOW}) {
            for (Stored candidate : allJobs.get(p)) {
                if (delivered >= maxPoll) {
                    break outer;
                }
                JobId id = candidate.jobId();
                if (currentPending.stream().anyMatch(s -> s.jobId().equals(id))) {
                    continue;
                }
                if (ackedByGroup.getOrDefault(groupId, Set.of()).contains(id)) {
                    continue;
                }
                String dedupKey = candidate.dedupKey();
                if (dedupKey != null && isSeen(dedupKey)) {
                    continue;
                }
                currentPending.add(candidate);
                if (dedupKey != null) {
                    markSeen(dedupKey);
                }
                delivered++;
                return Optional.of(new ClaimedJob(id, candidate.payload(), dedupKey, 1));
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void acknowledge(String groupId, ClaimedJob claimed) {
        pendingByGroup.computeIfAbsent(groupId, k -> new java.util.ArrayList<>()).removeIf(s -> s.jobId().equals(claimed.jobId()));
        ackedByGroup.computeIfAbsent(groupId, k -> new java.util.HashSet<>()).add(claimed.jobId());
    }

    @Override
    public synchronized int reclaimPending(int maxClaim) {
        int moved = 0;
        for (List<Stored> group : pendingByGroup.values()) {
            for (Stored entry : new java.util.ArrayList<>(group)) {
                if (moved >= maxClaim) {
                    break;
                }
                group.remove(entry);
                moved++;
            }
        }
        return moved;
    }

    @Override
    public PendingStats pendingStats() {
        long total = pendingByGroup.values().stream().mapToInt(List::size).sum();
        return new PendingStats(total, total);
    }

    @Override
    public synchronized int promoteDelayed() {
        long now = System.currentTimeMillis();
        List<Stored> promoted = new java.util.ArrayList<>();
        java.util.Map.Entry<Long, Stored> entry;
        while ((entry = delayed.pollFirstEntry()) != null && entry.getKey() <= now) {
            Stored stored = entry.getValue();
            allJobs.get(stored.priority()).add(stored);
            promoted.add(stored);
        }
        return promoted.size();
    }

    private boolean isSeen(String dedupKey) {
        Long expiry = seen.get(dedupKey);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry) {
            seen.remove(dedupKey);
            return false;
        }
        return true;
    }

    private void markSeen(String dedupKey) {
        seen.put(dedupKey, System.currentTimeMillis() + SEEN_TTL_MS);
    }

    @Override
    public void close() {
        pendingByGroup.clear();
        ackedByGroup.clear();
        seen.clear();
    }
}
