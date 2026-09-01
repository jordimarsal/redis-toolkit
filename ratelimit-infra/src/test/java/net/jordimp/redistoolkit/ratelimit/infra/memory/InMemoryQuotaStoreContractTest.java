package net.jordimp.redistoolkit.ratelimit.infra.memory;

import net.jordimp.redistoolkit.ratelimit.infra.contract.QuotaStoreContractTest;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;

public class InMemoryQuotaStoreContractTest extends QuotaStoreContractTest {

    @Override
    protected QuotaStore store() {
        return new InMemoryQuotaStore();
    }
}
