package net.jordimp.redistoolkit.jobqueue.usecase;

import net.jordimp.redistoolkit.jobqueue.domain.ClaimedJob;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;
import net.jordimp.redistoolkit.jobqueue.port.Metrics;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class WorkerLoop {

    private static final int DEFAULT_BATCH = 10;

    private final String groupId;
    private final QueueStore store;
    private final Metrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile Consumer<ClaimedJob> processor;
    private Thread thread;

    public WorkerLoop(String groupId, QueueStore store, Metrics metrics) {
        this.groupId = groupId;
        this.store = store;
        this.metrics = metrics;
    }

    public void setProcessor(Consumer<ClaimedJob> processor) {
        this.processor = processor;
    }

    public int pollAndProcess(int batchSize) {
        int processed = 0;
        for (int i = 0; i < batchSize && running.get(); i++) {
            Optional<ClaimedJob> claimed = store.claim(groupId, 1);
            if (claimed.isEmpty()) {
                break;
            }
            ClaimedJob job = claimed.get();
            try {
                processor.accept(job);
                metrics.delivered(groupId);
                store.acknowledge(groupId, job);
                processed++;
            } catch (RuntimeException e) {
                metrics.failed(groupId);
                throw e;
            }
        }
        return processed;
    }

    public void requestShutdown() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        thread = new Thread(this::runLoop, "worker-" + groupId);
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        while (running.get()) {
            int processed = pollAndProcess(DEFAULT_BATCH);
            if (processed == 0 && !running.get()) {
                break;
            }
        }
        drain();
    }

    public void drainAndWait() {
        running.set(false);
        drain();
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drain() {
        if (processor == null) {
            return;
        }
        pollAndProcess(DEFAULT_BATCH);
    }
}
