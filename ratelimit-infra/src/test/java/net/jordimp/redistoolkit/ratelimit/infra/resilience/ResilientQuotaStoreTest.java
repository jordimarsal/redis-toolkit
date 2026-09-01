package net.jordimp.redistoolkit.ratelimit.infra.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.prometheus.client.CollectorRegistry;
import java.time.Duration;
import java.time.Instant;
import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import net.jordimp.redistoolkit.ratelimit.infra.memory.InMemoryQuotaStore;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class ResilientQuotaStoreTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final QuotaKey KEY = new QuotaKey("1.2.3.4", Dimension.IP);

    @Test
    void degradedLocal_returnsFallbackDecision_whenPrimaryFails() {
        CollectorRegistry registry = new CollectorRegistry();
        FailingPrimary primary = new FailingPrimary(true);
        ResilientQuotaStore store = new ResilientQuotaStore(primary, new InMemoryQuotaStore(), FailurePolicy.DEGRADED_LOCAL, registry);

        Decision decision = store.evaluateAndConsume(KEY, RateLimitSpec.perMinute(5), NOW);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(Reason.OK);
        assertThat(registry.getSampleValue("ratelimit_store_failures_total")).isEqualTo(1.0);
        assertThat(registry.getSampleValue("ratelimit_degraded")).isEqualTo(1.0);
    }

    @Test
    void degradedLocal_admitsWithinLocalBudget_thenLimitsExceededWhenExhausted() {
        CollectorRegistry registry = new CollectorRegistry();
        FailingPrimary primary = new FailingPrimary(true);
        ResilientQuotaStore store = new ResilientQuotaStore(primary, new InMemoryQuotaStore(), FailurePolicy.DEGRADED_LOCAL, registry);
        RateLimitSpec spec = RateLimitSpec.of(2, Duration.ofSeconds(60), 2);

        Decision first = store.evaluateAndConsume(KEY, spec, NOW);
        Decision second = store.evaluateAndConsume(KEY, spec, NOW);
        Decision third = store.evaluateAndConsume(KEY, spec, NOW);

        assertThat(first.isAllowed()).isTrue();
        assertThat(second.isAllowed()).isTrue();
        assertThat(third.isAllowed()).isFalse();
        assertThat(third.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
        assertThat(third.retryAfterSeconds()).isGreaterThan(0L);
    }

    @Test
    void recovery_resetsDegradedGauge_whenPrimarySucceedsAgain() {
        CollectorRegistry registry = new CollectorRegistry();
        FailingPrimary primary = new FailingPrimary(true);
        ResilientQuotaStore store = new ResilientQuotaStore(primary, new InMemoryQuotaStore(), FailurePolicy.DEGRADED_LOCAL, registry);

        store.evaluateAndConsume(KEY, RateLimitSpec.perMinute(5), NOW);
        assertThat(registry.getSampleValue("ratelimit_degraded")).isEqualTo(1.0);

        primary.fail = false;
        Decision decision = store.evaluateAndConsume(KEY, RateLimitSpec.perMinute(5), NOW);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(registry.getSampleValue("ratelimit_degraded")).isEqualTo(0.0);
        assertThat(registry.getSampleValue("ratelimit_store_failures_total")).isEqualTo(1.0);
    }

    @Test
    void failClosed_rejectsStoreUnavailable_withoutConsultingFallback() {
        CollectorRegistry registry = new CollectorRegistry();
        FailingPrimary primary = new FailingPrimary(true);
        RecordingFallback fallback = new RecordingFallback();
        ResilientQuotaStore store = new ResilientQuotaStore(primary, fallback, FailurePolicy.FAIL_CLOSED, registry);

        Decision decision = store.evaluateAndConsume(KEY, RateLimitSpec.perMinute(5), NOW);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.STORE_UNAVAILABLE);
        assertThat(fallback.calls).isZero();
        assertThat(registry.getSampleValue("ratelimit_store_failures_total")).isEqualTo(1.0);
        assertThat(registry.getSampleValue("ratelimit_degraded")).isEqualTo(1.0);
    }

    @Test
    void noExceptionEscapes_whenBothStoresFail() {
        CollectorRegistry registry = new CollectorRegistry();
        QuotaStore brokenLocal = (key, spec, now) -> {
            throw new IllegalStateException("local also down");
        };
        ResilientQuotaStore store = new ResilientQuotaStore(new FailingPrimary(true), brokenLocal, FailurePolicy.DEGRADED_LOCAL, registry);

        assertThatCode(() -> {
            Decision decision = store.evaluateAndConsume(KEY, RateLimitSpec.perMinute(5), NOW);
            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(Reason.STORE_UNAVAILABLE);
        }).doesNotThrowAnyException();
    }

    @Test
    void close_delegatesToPrimary_whenAutoCloseable() {
        CollectorRegistry registry = new CollectorRegistry();
        ClosingPrimary primary = new ClosingPrimary();
        try (ResilientQuotaStore store = new ResilientQuotaStore(primary, new InMemoryQuotaStore(), FailurePolicy.DEGRADED_LOCAL, registry)) {
            assertThat(primary.closed).isFalse();
        }
        assertThat(primary.closed).isTrue();
    }

    private static final class FailingPrimary implements QuotaStore {
        boolean fail;

        FailingPrimary(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
            if (fail) {
                throw new JedisConnectionException("simulated redis outage");
            }
            return Decision.ok(spec.limit() - 1L, spec.limit());
        }
    }

    private static final class RecordingFallback implements QuotaStore {
        int calls;

        @Override
        public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
            calls++;
            return Decision.ok(spec.limit(), spec.limit());
        }
    }

    private static final class ClosingPrimary implements QuotaStore, AutoCloseable {
        boolean closed;

        @Override
        public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
            return Decision.ok(spec.limit(), spec.limit());
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
