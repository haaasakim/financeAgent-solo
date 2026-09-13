package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReturnsTest {

    @Test
    @DisplayName("固定输入对拍：前 period 格为 null")
    void computesFixedFixture() {
        Double[] returns = Returns.simple(new double[] {10, 11, 12, 13}, 2);

        assertThat(returns[0]).isNull();
        assertThat(returns[1]).isNull();
        assertThat(returns[2]).isCloseTo(0.2, within(1e-12));
        assertThat(returns[3]).isCloseTo(13.0 / 11.0 - 1.0, within(1e-12));
    }

    @Test
    @DisplayName("回看不足与空序列全为 null / 空数组")
    void shortInputs() {
        assertThat(Returns.simple(new double[] {10, 11}, 2)).containsExactly(null, null);
        assertThat(Returns.simple(new double[] {}, 20)).isEmpty();
    }

    @Test
    @DisplayName("基准价为 0 时为 null，不产生除零")
    void zeroBaseReturnsNull() {
        Double[] returns = Returns.simple(new double[] {0, 10}, 1);

        assertThat(returns[1]).isNull();
    }

    @Test
    @DisplayName("非法周期直接拒绝")
    void rejectsInvalidPeriod() {
        assertThatThrownBy(() -> Returns.simple(new double[] {1}, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
