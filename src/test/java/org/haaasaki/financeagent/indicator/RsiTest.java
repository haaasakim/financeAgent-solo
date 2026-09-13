package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RsiTest {

    @Test
    @DisplayName("单调上涨 → 100，单调下跌 → 0，横盘 → 50")
    void boundaryCases() {
        assertThat(Rsi.rsi(new double[] {1, 2, 3, 4, 5}, 3)[4]).isCloseTo(100.0, within(1e-9));
        assertThat(Rsi.rsi(new double[] {5, 4, 3, 2, 1}, 3)[4]).isCloseTo(0.0, within(1e-9));
        assertThat(Rsi.rsi(new double[] {5, 5, 5, 5, 5}, 3)[4]).isCloseTo(50.0, within(1e-9));
    }

    @Test
    @DisplayName("固定输入对拍：Wilder 平滑的手算结果")
    void computesFixedFixture() {
        // closes = [10, 11, 10.5, 11.5, 11, 12]，period=3（Python 独立复算）
        Double[] rsi = Rsi.rsi(new double[] {10, 11, 10.5, 11.5, 11, 12}, 3);

        assertThat(rsi[0]).isNull();
        assertThat(rsi[1]).isNull();
        assertThat(rsi[2]).isNull();
        assertThat(rsi[3]).isCloseTo(80.0, within(1e-9));
        assertThat(rsi[4]).isCloseTo(61.53846153846154, within(1e-9));
        assertThat(rsi[5]).isCloseTo(77.27272727272728, within(1e-9));
    }

    @Test
    @DisplayName("序列短于 period + 1 时全为 null")
    void insufficientWarmup() {
        assertThat(Rsi.rsi(new double[] {10, 11, 12}, 3))
                .containsExactly(null, null, null);
    }

    @Test
    @DisplayName("空序列返回空数组")
    void emptyInput() {
        assertThat(Rsi.rsi(new double[] {}, 6)).isEmpty();
    }

    @Test
    @DisplayName("非法周期直接拒绝")
    void rejectsInvalidPeriod() {
        assertThatThrownBy(() -> Rsi.rsi(new double[] {1, 2}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
