package net.jordimp.redistoolkit.ratelimit.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.jordimp.redistoolkit.ratelimit.api.dto.ApiResponse;
import net.jordimp.redistoolkit.ratelimit.domain.Decision;
import net.jordimp.redistoolkit.ratelimit.domain.Reason;
import org.junit.jupiter.api.Test;

class DecisionMapperTest {

    private final DecisionMapper mapper = new DecisionMapper();

    @Test
    void r6_allowedMapsTo200WithHeadersAndBody() {
        Decision decision = Decision.ok(9L, 10L);

        ApiResponse<?> response = mapper.toResponse(decision, "hello");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hello");
        assertThat(response.headers())
                .containsEntry("X-RateLimit-Limit", "10")
                .containsEntry("X-RateLimit-Remaining", "9")
                .containsKey("X-RateLimit-Reset");
    }

    @Test
    void r7_limitExceededMapsTo429WithRetryAfter() {
        Decision decision = Decision.rejected(Reason.LIMIT_EXCEEDED, 10L, Duration.ofSeconds(3));

        ApiResponse<?> response = mapper.toResponse(decision, null);

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.headers())
                .containsEntry("Retry-After", "3")
                .containsEntry("X-RateLimit-Limit", "10");
    }

    @Test
    void r8_storeUnavailableMapsTo503DistinctFrom429() {
        Decision decision = Decision.rejected(Reason.STORE_UNAVAILABLE, 10L, null);

        assertThat(mapper.toResponse(decision, null).status()).isEqualTo(503);
    }

    @Test
    void r8_configErrorMapsTo500DistinctFrom429() {
        Decision decision = Decision.rejected(Reason.CONFIG_ERROR, 10L, null);

        assertThat(mapper.toResponse(decision, null).status()).isEqualTo(500);
    }
}
