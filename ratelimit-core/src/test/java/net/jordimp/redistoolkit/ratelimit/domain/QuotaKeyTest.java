package net.jordimp.redistoolkit.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuotaKeyTest {

    @Test
    void r04_rejectsBlankValue() {
        assertThatThrownBy(() -> new QuotaKey(null, Dimension.TENANT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaKey("", Dimension.IP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaKey("   ", Dimension.MODEL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void r05_renderFormat() {
        assertThat(new QuotaKey("acme", Dimension.TENANT).render())
                .isEqualTo("ratelimit:tenant:acme");
        assertThat(new QuotaKey("10.0.0.1", Dimension.IP).render())
                .isEqualTo("ratelimit:ip:10.0.0.1");
    }

    @Test
    void r06_withDimensionChangesOnlyDimension() {
        QuotaKey original = new QuotaKey("acme", Dimension.TENANT);
        QuotaKey changed = original.withDimension(Dimension.MODEL);
        assertThat(changed.dimension()).isEqualTo(Dimension.MODEL);
        assertThat(changed.value()).isEqualTo("acme");
        assertThat(original.dimension()).isEqualTo(Dimension.TENANT);
    }

    @Test
    void r07_equalityRequiresValueAndDimension() {
        QuotaKey a = new QuotaKey("acme", Dimension.TENANT);
        assertThat(a).isEqualTo(new QuotaKey("acme", Dimension.TENANT));
        assertThat(a).isNotEqualTo(new QuotaKey("acme", Dimension.IP));
        assertThat(a).isNotEqualTo(new QuotaKey("other", Dimension.TENANT));
    }
}
