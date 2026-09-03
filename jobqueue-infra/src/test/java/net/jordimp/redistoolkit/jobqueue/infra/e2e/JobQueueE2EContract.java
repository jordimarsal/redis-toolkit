package net.jordimp.redistoolkit.jobqueue.infra.e2e;

import net.jordimp.redistoolkit.jobqueue.domain.DedupKey;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.infra.metrics.InMemoryMetrics;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.usecase.WorkerLoop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end behaviour of the whole queue path (submit -&gt; claim -&gt; process -&gt; ack) exercised through
 * {@link WorkerLoop} against a real store. This mirrors the contract-suite approach already used for the
 * store layer: one abstract base describing WHAT must hold, and one concrete subclass per store
 * (in-memory now, Redis via Testcontainers). The assertions cover what an operator actually cares about
 * -- every job delivered exactly once, priority ordering, dedup, acking preventing redelivery, delayed
 * promotion and safe concurrent promotion -- rather than internal counters that live behind the port.
 */
public abstract class JobQueueE2EContract {

    protected static final String GROUP = "e2e-group";

    /** Fresh, isolated store handed to each test method. */
    protected abstract QueueStore createStore();

    private QueueStore store;
    private InMemoryMetrics metrics;
    private WorkerLoop worker;

    @BeforeEach
    void setUp() {
        this.store = createStore();
        metrics = new InMemoryMetrics();
        worker = new WorkerLoop(GROUP, store, metrics);
    }

    protected Payload payload(String text) {
        return Payload.of(text.getBytes());
    }

    protected long delivered() {
        return metrics.count(GROUP, "delivered");
    }

    /** Drain through the WorkerLoop's own processing path until the queue is empty (deterministic). */
    private void processAll() {
        worker.pollAndProcess(Integer.MAX_VALUE);
    }

    @Test
    void everySubmittedJobIsDeliveredExactlyOnceThroughWorker() {
        int total = 20;
        for (int i = 0; i < total; i++) {
            store.submit(payload("job" + i), Priority.NORMAL, null);
        }
        worker.setProcessor(job -> { });
        processAll();
        assertThat(delivered()).as("every submitted job is delivered").isEqualTo(total);
        assertThat(store.pendingStats().unackedEntries()).as("nothing stays pending").isZero();
    }

    @Test
    void higherPriorityJobsAreProcessedBeforeLowerPriority() {
        store.submit(payload("low"), Priority.LOW, null);
        store.submit(payload("high"), Priority.HIGH, null);
        store.submit(payload("normal"), Priority.NORMAL, null);
        List<String> order = new ArrayList<>();
        worker.setProcessor(job -> order.add(new String(job.payload().data())));
        processAll();
        assertThat(order).containsExactly("high", "normal", "low");
    }

    @Test
    void dedupKeyPreventsRepeatedDelivery() {
        store.submit(payload("dup"), Priority.NORMAL, DedupKey.of("e2e-dedup"));
        worker.setProcessor(job -> { });
        processAll();
        assertThat(delivered()).as("duplicate collapses to a single delivery").isOne();
    }

    @Test
    void acknowledgedJobIsNotReclaimedAfterProcessing() {
        for (int i = 0; i < 5; i++) {
            store.submit(payload("a" + i), Priority.NORMAL, null);
        }
        worker.setProcessor(job -> { });
        processAll();
        assertThat(store.reclaimPending(10)).as("already-acked jobs are not reprocessed").isZero();
        assertThat(store.pendingStats().unackedEntries()).as("no unacked entries remain").isZero();
        assertThat(delivered()).isEqualTo(5);
    }

    @Test
    void delayedJobIsPromotedAndProcessedAfterRunAt() {
        store.submitDelayed(payload("delayed"), Priority.NORMAL, null, Instant.now().minusSeconds(60));
        assertThat(store.promoteDelayed()).as("the due delayed job is promoted").isOne();
        List<String> order = new ArrayList<>();
        worker.setProcessor(job -> order.add(new String(job.payload().data())));
        processAll();
        assertThat(order).contains("delayed");
    }

    @Test
    void concurrentPromotionDeliversEachJobExactlyOnce() throws Exception {
        int total = 50;
        for (int i = 0; i < total; i++) {
            // Distinct past timestamps so every delayed job is a separate entry in any store (an in-memory
            // TreeMap keyed by runAt would otherwise collapse jobs sharing the same millisecond).
            store.submitDelayed(payload("j" + i), Priority.NORMAL, null, Instant.now().minusSeconds(total - i));
        }
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                tasks.add(() -> store.promoteDelayed());
            }
            List<Future<Integer>> futures = exec.invokeAll(tasks);
            for (Future<Integer> future : futures) {
                future.get();
            }
        } finally {
            exec.shutdownNow();
        }
        worker.setProcessor(job -> { });
        processAll();
        assertThat(delivered()).as("each promoted job delivered exactly once").isEqualTo(total);
        assertThat(store.pendingStats().unackedEntries()).isZero();
    }
}
