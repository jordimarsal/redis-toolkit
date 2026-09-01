package net.jordimp.redistoolkit.ratelimit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jordimp.redistoolkit.ratelimit.domain.Dimension;
import net.jordimp.redistoolkit.ratelimit.domain.QuotaKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class KeyExtractorTest {

    private final KeyExtractor extractor = new KeyExtractor();

    @Test
    void r2_mapsTenantDimensionToQuotaKey() {
        QuotaKey key = extractor.extract(Dimension.TENANT, "acme");

        assertThat(key).isEqualTo(new QuotaKey("acme", Dimension.TENANT));
        assertThat(key.render()).isEqualTo("ratelimit:tenant:acme");
    }

    @ParameterizedTest
    @EnumSource(Dimension.class)
    void r2_buildsKeyForEveryDimension(Dimension dimension) {
        QuotaKey key = extractor.extract(dimension, "v1");

        assertThat(key.value()).isEqualTo("v1");
        assertThat(key.dimension()).isEqualTo(dimension);
    }

    @Test
    void r3_rejectsNullValue() {
        assertThatThrownBy(() -> extractor.extract(Dimension.IP, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
    }

    @Test
    void r3_rejectsBlankValue() {
        assertThatThrownBy(() -> extractor.extract(Dimension.MODEL, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
