package net.jordimp.redistoolkit.jobqueue.infra.memory;

import net.jordimp.redistoolkit.jobqueue.contract.QueueStoreContractTest;
import net.jordimp.redistoolkit.jobqueue.port.QueueStore;

public final class InMemoryQueueStoreContractTest extends QueueStoreContractTest {

    @Override
    protected QueueStore store() {
        return new InMemoryQueueStore("contract");
    }
}
