package net.jordimp.redistoolkit.ratelimit.infra.resilience;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.time.Duration;
import java.time.Instant;
import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import net.jordimp.redistoolkit.ratelimit.port.QuotaStore;

public final class ResilientQuotaStore implements QuotaStore, AutoCloseable {

    private static final String FAILURE_COUNTER = "ratelimit_store_failures_total";
    private static final String DEGRADED_GAUGE = "ratelimit_degraded";

    private final QuotaStore primary;
    private final QuotaStore localFallback;
    private final FailurePolicy policy;
    private final Counter failures;
    private final Gauge degraded;

    public ResilientQuotaStore(QuotaStore primary, QuotaStore localFallback, FailurePolicy policy, CollectorRegistry registry) {
        if (primary == null || localFallback == null || policy == null || registry == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        this.primary = primary;
        this.localFallback = localFallback;
        this.policy = policy;
        this.failures = Counter.build()
                .name(FAILURE_COUNTER)
                .help("Primary quota store evaluation failures caught by the resilient decorator.")
                .create();
        registry.register(this.failures);
        this.degraded = Gauge.build()
                .name(DEGRADED_GAUGE)
                .help("1 while the last evaluation used the fallback or fail-closed path, 0 when the primary succeeded.")
                .create();
        registry.register(this.degraded);
    }

    @Override
    public Decision evaluateAndConsume(QuotaKey key, RateLimitSpec spec, Instant now) {
        try {
            Decision decision = primary.evaluateAndConsume(key, spec, now);
            degraded.set(0);
            return decision;
        } catch (RuntimeException e) {
            failures.inc();
            degraded.set(1);
            if (policy == FailurePolicy.FAIL_CLOSED) {
                return Decision.rejected(Reason.STORE_UNAVAILABLE, spec.limit(), Duration.ZERO);
            }
            try {
                return localFallback.evaluateAndConsume(key, spec, now);
            } catch (RuntimeException fallbackFailure) {
                return Decision.rejected(Reason.STORE_UNAVAILABLE, spec.limit(), Duration.ZERO);
            }
        }
    }

    @Override
    public void close() {
        if (primary instanceof AutoCloseable closable) {
            try {
                closable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close primary quota store", e);
            }
        }
    }
}
