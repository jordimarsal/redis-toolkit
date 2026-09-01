package net.jordimp.redistoolkit.ratelimit.infra.contract;

import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared contract for every {@link QuotaStore} implementation (in-memory now, Redis later).
 * Concrete subclasses supply a fresh store via {@link #store()}; the suite is deterministic
 * because time is injected per call as an explicit {@code Instant}.
 */
public abstract class QuotaStoreContractTest {

    protected abstract QuotaStore store();

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    private static QuotaKey key(String value) {
        return new QuotaKey(value, Dimension.TENANT);
    }

    @Test
    public void r2_freshStoreAdmitsUpToBurstThenRejects() {
        RateLimitSpec spec = new RateLimitSpec(1, Duration.ofSeconds(60), 5);
        QuotaStore s = store();
        for (int i = 1; i <= 5; i++) {
            assertThat(s.evaluateAndConsume(key("u"), spec, t0).isAllowed())
                    .as("burst attempt %d", i).isTrue();
        }
        assertThat(s.evaluateAndConsume(key("u"), spec, t0).isAllowed()).isFalse();
    }

    @Test
    public void r3_admissionReportsRemainingLimitAndHeaders() {
        RateLimitSpec spec = new RateLimitSpec(10, Duration.ofSeconds(60), 10);
        Decision d = store().evaluateAndConsume(key("u"), spec, t0);
        assertThat(d.isAllowed()).isTrue();
        assertThat(d.remaining()).isEqualTo(9L);
        assertThat(d.limit()).isEqualTo(10L);
        assertThat(d.reason()).isEqualTo(Reason.OK);
        Map<String, String> h = d.headers();
        assertThat(h)
                .containsEntry("X-RateLimit-Limit", "10")
                .containsEntry("X-RateLimit-Remaining", "9");
    }

    @Test
    public void r4_rejectionCarriesPositiveRetryAfterAndReason() {
        RateLimitSpec spec = new RateLimitSpec(1, Duration.ofSeconds(60), 1);
        QuotaStore s = store();
        assertThat(s.evaluateAndConsume(key("u"), spec, t0).isAllowed()).isTrue();
        Decision rej = s.evaluateAndConsume(key("u"), spec, t0);
        assertThat(rej.isAllowed()).isFalse();
        assertThat(rej.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
        assertThat(rej.remaining()).isEqualTo(0L);
        assertThat(rej.retryAfterSeconds()).isNotNull().isGreaterThan(0L);
        assertThat(rej.headers()).containsKey("Retry-After");
    }

    @Test
    public void r5_refillReAdmitsExhaustedKeyAfterWindow() {
        RateLimitSpec spec = new RateLimitSpec(2, Duration.ofSeconds(60), 2);
        QuotaStore s = store();
        assertThat(s.evaluateAndConsume(key("u"), spec, t0).isAllowed()).isTrue();
        assertThat(s.evaluateAndConsume(key("u"), spec, t0).isAllowed()).isTrue();
        assertThat(s.evaluateAndConsume(key("u"), spec, t0).isAllowed()).isFalse();
        Instant later = t0.plus(Duration.ofMinutes(60));
        assertThat(s.evaluateAndConsume(key("u"), spec, later).isAllowed()).isTrue();
    }

    @Test
    public void r6_distinctKeysAreIndependent() {
        RateLimitSpec spec = new RateLimitSpec(1, Duration.ofSeconds(60), 1);
        QuotaStore s = store();
        assertThat(s.evaluateAndConsume(key("A"), spec, t0).isAllowed()).isTrue();
        assertThat(s.evaluateAndConsume(key("A"), spec, t0).isAllowed()).isFalse();
        assertThat(s.evaluateAndConsume(key("B"), spec, t0).isAllowed()).isTrue();
    }

    @Test
    public void r7_concurrentAdmitsExactlyTheLimit() throws Exception {
        int limit = 10;
        int attempts = 100;
        RateLimitSpec spec = new RateLimitSpec(limit, Duration.ofSeconds(60), limit);
        QuotaStore s = store();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return s.evaluateAndConsume(key("u"), spec, t0).isAllowed();
            }));
        }
        start.countDown();
        long admitted = 0L;
        for (Future<Boolean> f : results) {
            if (Boolean.TRUE.equals(f.get())) {
                admitted++;
            }
        }
        pool.shutdown();
        assertThat(admitted).isEqualTo(limit);
    }

    @Test
    public void r8_identicalInputsProduceIdenticalDecisions() {
        RateLimitSpec spec = new RateLimitSpec(5, Duration.ofSeconds(60), 5);
        Decision a = store().evaluateAndConsume(key("X"), spec, t0);
        Decision b = store().evaluateAndConsume(key("X"), spec, t0);
        assertThat(a.allowed()).isEqualTo(b.allowed());
        assertThat(a.remaining()).isEqualTo(b.remaining());
        assertThat(a.limit()).isEqualTo(b.limit());
        assertThat(a.reason()).isEqualTo(b.reason());
    }
}
