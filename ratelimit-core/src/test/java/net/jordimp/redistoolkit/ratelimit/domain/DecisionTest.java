package net.jordimp.redistoolkit.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionTest {

    private final Decision ok = Decision.ok(5, 10);
    private final Decision rejected =
            Decision.rejected(Reason.LIMIT_EXCEEDED, 10, Duration.ofSeconds(45));

    @Test
    void r08_exposesValuesAndIsAllowed() {
        assertThat(ok.isAllowed()).isTrue();
        assertThat(ok.remaining()).isEqualTo(5);
        assertThat(ok.limit()).isEqualTo(10);
        assertThat(ok.reason()).isEqualTo(Reason.OK);
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
    }

    @Test
    void r09_includesRetryAfterWhenPositive() {
        Map<String, String> headers = rejected.headers();
        assertThat(headers).containsEntry("Retry-After", "45");
    }

    @Test
    void r10_omitsRetryAfterWhenAbsent() {
        assertThat(ok.headers()).doesNotContainKey("Retry-After");
    }

    @Test
    void r11_alwaysIncludesRateLimitHeaders() {
        for (Decision d : new Decision[] { ok, rejected }) {
            Map<String, String> h = d.headers();
            assertThat(h)
                    .containsKeys("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");
        }
    }
}
