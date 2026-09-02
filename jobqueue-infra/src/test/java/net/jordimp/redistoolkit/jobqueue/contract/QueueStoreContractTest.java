package net.jordimp.redistoolkit.jobqueue.contract;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.DedupKey;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.port.SubmitResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared behavioral contract every {@link QueueStore} implementation must satisfy (in-memory now,
 * Redis later). Concrete subclasses supply a fresh store via {@link #store()}.
 */
public abstract class QueueStoreContractTest {

    protected abstract QueueStore store();

    protected Payload payload(String text) {
        return Payload.of(text.getBytes());
    }

    @Test
    void submitPersistsUnderPriorityAndReturnsAssignedId() {
        SubmitResult result = store().submit(payload("hi"), Priority.NORMAL, null);
        assertThat(result.jobId()).isNotNull();
    }

    @Test
    void higherPriorityIsClaimedBeforeLowerPriority() throws Exception {
        QueueStore s = store();
        s.submit(payload("low"), Priority.LOW, null);
        s.submit(payload("high"), Priority.HIGH, null);
        s.submit(payload("normal"), Priority.NORMAL, null);
        Optional<ClaimedJob> first = s.claim("g", 1);
        assertThat(first).isPresent();
        assertThat(new String(first.get().payload().data())).isEqualTo("high");
    }

    @Test
    void eachGroupDeliversEverySubmittedJobExactlyOnce() {
        QueueStore s = store();
        int total = 20;
        for (int i = 0; i < total; i++) {
            s.submit(payload("job" + i), Priority.NORMAL, null);
        }
        List<ClaimedJob> delivered = new ArrayList<>();
        Optional<ClaimedJob> claimed;
        while ((claimed = s.claim("g", 1)).isPresent()) {
            delivered.add(claimed.get());
        }
        Set<String> ids = delivered.stream().map(j -> j.jobId().raw()).collect(Collectors.toSet());
        assertThat(delivered.size()).as("every job delivered").isEqualTo(total);
        assertThat(ids).as("each job delivered once").hasSize(total);
    }

    @Test
    void acknowledgedEntryIsNotRedeliveredAfterReclaim() {
        QueueStore s = store();
        s.submit(payload("a"), Priority.NORMAL, null);
        Optional<ClaimedJob> claimed = s.claim("g", 1);
        assertThat(claimed).isPresent();
        s.acknowledge("g", claimed.get());
        assertThat(s.reclaimPending(10)).isZero();
        assertThat(s.pendingStats().unackedEntries()).isZero();
        assertThat(s.claim("g", 1)).isEmpty();
    }

    @Test
    void reclaimMakesUnackedEntryAvailableAgain() {
        QueueStore s = store();
        s.submit(payload("x"), Priority.NORMAL, null);
        Optional<ClaimedJob> first = s.claim("g", 1);
        assertThat(first).isPresent();
        assertThat(s.reclaimPending(1)).isOne();
        Optional<ClaimedJob> again = s.claim("g", 1);
        assertThat(again).isPresent();
        assertThat(new String(again.get().payload().data())).isEqualTo("x");
    }

    @Test
    void dedupKeyCollapsesRepeatedDelivery() {
        QueueStore s = store();
        s.submit(payload("d"), Priority.NORMAL, DedupKey.of("dedup-1"));
        Optional<ClaimedJob> first = s.claim("g", 1);
        assertThat(first).isPresent();
        Optional<ClaimedJob> second = s.claim("g", 1);
        assertThat(second).isEmpty();
    }

    @Test
    void delayedJobIsPromotedOnceRunAtHasPassed() {
        QueueStore s = store();
        Instant past = Instant.now().minusSeconds(60);
        s.submitDelayed(payload("late"), Priority.NORMAL, null, past);
        assertThat(s.promoteDelayed()).isOne();
        Optional<ClaimedJob> claimed = s.claim("g", 1);
        assertThat(claimed).isPresent();
        assertThat(new String(claimed.get().payload().data())).isEqualTo("late");
    }

    @Test
    void futureDelayedJobIsNotPromotedYet() {
        QueueStore s = store();
        Instant future = Instant.now().plusSeconds(3600);
        s.submitDelayed(payload("soon"), Priority.NORMAL, null, future);
        assertThat(s.promoteDelayed()).isZero();
        assertThat(s.claim("g", 1)).isEmpty();
    }
}
