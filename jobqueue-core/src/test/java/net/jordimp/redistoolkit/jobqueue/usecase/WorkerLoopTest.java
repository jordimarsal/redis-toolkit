package net.jordimp.redistoolkit.jobqueue.usecase;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.domain.Payload;
import net.jordimp.redistoolkit.jobqueue.domain.Priority;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.support.FakeQueueStore;
import net.jordimp.redistoolkit.jobqueue.support.RecordingMetrics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    void failureStormStopsTheLoop_visiblyAndSkipsFinalDrain() {
        for (int i = 0; i < 101; i++) {
            store.submit(Payload.of(new byte[]{(byte) i}), Priority.NORMAL, null);
        }
        WorkerLoop loop = new WorkerLoop(GROUP, store, metrics);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        Consumer<ClaimedJob> flaky = job -> {
            if (attempts.incrementAndGet() <= 100) {
                throw new IllegalStateException("boom");
            }
            processed.incrementAndGet();
        };
        loop.setProcessor(flaky);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err, true));
        try {
            for (int i = 0; i < 100; i++) {
                try {
                    loop.pollAndProcess(1);
                } catch (IllegalStateException expected) {
                    // expected until the threshold trips
                }
            }
        } finally {
            System.setErr(originalErr);
        }
        assertThat(loop.isStoppedByFailures()).as("loop must be observably stopped").isTrue();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("consecutive failures");
        loop.drain();
        assertThat(processed.get()).as("drain must be skipped after a failure storm").isZero();
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
