package net.jordimp.redistoolkit.ratelimit.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    private static final Class<?>[] DOMAIN_TYPES = {
            net.jordimp.redistoolkit.ratelimit.domain.Decision.class,
            net.jordimp.redistoolkit.ratelimit.domain.QuotaKey.class,
            net.jordimp.redistoolkit.ratelimit.domain.RateLimitSpec.class,
            net.jordimp.redistoolkit.ratelimit.domain.Dimension.class,
            net.jordimp.redistoolkit.ratelimit.domain.Reason.class
    };

    @Test
    void r9_exposesNoDomainTypesOnPublicSurface() throws Exception {
        for (Field field : ApiResponse.class.getDeclaredFields()) {
            boolean leaks = false;
            for (Class<?> domainType : DOMAIN_TYPES) {
                if (domainType.isAssignableFrom(field.getType())) {
                    leaks = true;
                }
            }
            assertThat(leaks).as("field '%s' must not be a domain type", field.getName()).isFalse();
        }
    }
}
