package net.jordimp.redistoolkit.jobqueue.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityTest {

    @Test
    void higherPriorityHasLowerRank() {
        assertThat(Priority.HIGH.rank()).isLessThan(Priority.NORMAL.rank());
        assertThat(Priority.NORMAL.rank()).isLessThan(Priority.LOW.rank());
    }

    @Test
    void highPrecedesNormalAndLow() {
        assertThat(Priority.HIGH.higherThan(Priority.NORMAL)).isTrue();
        assertThat(Priority.HIGH.higherThan(Priority.LOW)).isTrue();
    }

    @Test
    void normalPrecedesOnlyLow() {
        assertThat(Priority.NORMAL.higherThan(Priority.HIGH)).isFalse();
        assertThat(Priority.NORMAL.higherThan(Priority.LOW)).isTrue();
    }
}
