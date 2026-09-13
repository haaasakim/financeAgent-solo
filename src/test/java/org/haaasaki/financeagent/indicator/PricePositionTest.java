package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricePositionTest {

    @Test
    @DisplayName("固定输入对拍：窗口 3 的滚动最高值")
    void computesFixedFixture() {
        Double[] position = PricePosition.pctFromHigh(new double[] {10, 12, 11}, 3);

        assertThat(position[0]).isNull();
        assertThat(position[1]).isNull();
        assertThat(position[2]).isCloseTo(11.0 / 12.0 - 1.0, within(1e-12));
    }

    @Test
    @DisplayName("窗口 2：第二格刷新高点为 0，回落为负")
    void windowOfTwo() {
        Double[] position = PricePosition.pctFromHigh(new double[] {10, 12, 11}, 2);

        assertThat(position[1]).isCloseTo(0.0, within(1e-12));
        assertThat(position[2]).isCloseTo(11.0 / 12.0 - 1.0, within(1e-12));
    }

    @Test
    @DisplayName("窗口 1 恒为 0")
    void windowOfOne() {
        Double[] position = PricePosition.pctFromHigh(new double[] {7, 8}, 1);

        assertThat(position[0]).isCloseTo(0.0, within(1e-12));
        assertThat(position[1]).isCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("滚动窗口正确淘汰过期高点（O(n) 单调队列）")
    void rollingWindowExpiresOldHigh() {
        // 窗口 3：索引 3 时 12 已过期，最高值应为 8
        Double[] position = PricePosition.pctFromHigh(new double[] {12, 7, 8, 6}, 3);

        assertThat(position[3]).isCloseTo(6.0 / 8.0 - 1.0, within(1e-12));
    }

    @Test
    @DisplayName("回看不足全为 null；空序列为空数组")
    void insufficientWarmup() {
        assertThat(PricePosition.pctFromHigh(new double[] {10, 11}, 5))
                .containsExactly(null, null);
        assertThat(PricePosition.pctFromHigh(new double[] {}, 250)).isEmpty();
    }

    @Test
    @DisplayName("非法窗口直接拒绝")
    void rejectsInvalidWindow() {
        assertThatThrownBy(() -> PricePosition.pctFromHigh(new double[] {1}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
