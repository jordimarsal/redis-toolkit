package net.jordimp.redistoolkit.jobqueue.infra.memory;

import net.jordimp.redistoolkit.jobqueue.infra.e2e.JobQueueE2EContract;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;

import java.util.concurrent.atomic.AtomicInteger;

/** End-to-end path through WorkerLoop against the in-memory store. */
public final class InMemoryQueueStoreE2ETest extends JobQueueE2EContract {

    private static final AtomicInteger counter = new AtomicInteger();

    @Override
    protected QueueStore createStore() {
        return new InMemoryQueueStore("e2e-" + counter.incrementAndGet());
    }
}
