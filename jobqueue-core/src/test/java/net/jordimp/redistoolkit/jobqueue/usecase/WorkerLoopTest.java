package net.jordimp.redistoolkit.jobqueue.usecase;

import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.support.FakeQueueStore;
import net.jordimp.redistoolkit.jobqueue.support.RecordingMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerLoopTest {

    private final QueueStore store = new FakeQueueStore();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private static final String GROUP = "g1";

    @Test
    void processesAndAcknowledgesEveryJob() {
        for (int i = 0; i < 3; i++) {
            store.submit(Payload.of(new byte[]{(byte) i}), Priority.NORMAL, null);
        }
        WorkerLoop loop = new WorkerLoop(GROUP, store, metrics);
        loop.setProcessor(job -> {
        });
        int processed = loop.pollAndProcess(10);
        assertThat(processed).isEqualTo(3);
        assertThat(metrics.count(GROUP, "delivered")).isEqualTo(3);
        assertThat(store.pendingStats().unackedEntries()).as("all jobs acknowledged").isZero();
    }

    @Test
    void failedJobIsRecordedButNeverAcknowledged() {
        store.submit(Payload.of(new byte[]{1}), Priority.NORMAL, null);
        WorkerLoop loop = new WorkerLoop(GROUP, store, metrics);
        loop.setProcessor(job -> {
            throw new IllegalStateException("boom");
        });
        boolean threw = false;
        try {
            loop.pollAndProcess(10);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        assertThat(threw).isTrue();
        assertThat(metrics.count(GROUP, "failed")).isGreaterThan(0);
        assertThat(store.pendingStats().unackedEntries()).as("failed job stays unacknowledged").isEqualTo(1);
    }

    @Test
    void stopsClaimingAfterShutdownRequested() {
        for (int i = 0; i < 4; i++) {
            store.submit(Payload.of(new byte[]{(byte) i}), Priority.NORMAL, null);
        }
        WorkerLoop loop = new WorkerLoop(GROUP, store, metrics);
        loop.requestShutdown();
        int processed = loop.pollAndProcess(10);
        assertThat(processed).as("no claim after shutdown").isZero();
        assertThat(loop.isRunning()).isFalse();
    }
}
