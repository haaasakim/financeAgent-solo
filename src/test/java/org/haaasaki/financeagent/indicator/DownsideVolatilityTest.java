package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DownsideVolatilityTest {

    @Test
    @DisplayName("固定输入对拍：只取负收益的样本标准差（忽略正收益与零收益）")
    void computesFixedFixture() {
        // 收益：-0.1, -0.2, +0.1, -0.1；负收益子集 {-0.1, -0.2, -0.1}
        double[] closes = {100.0, 90.0, 72.0, 79.2, 71.28};
        Double[] volatility = DownsideVolatility.ofReturns(closes, 4);

        double[] negatives = {-0.1, -0.2, -0.1};
        double mean = (negatives[0] + negatives[1] + negatives[2]) / 3.0;
        double expected = Math.sqrt(
                (Math.pow(negatives[0] - mean, 2)
                        + Math.pow(negatives[1] - mean, 2)
                        + Math.pow(negatives[2] - mean, 2)) / 2.0);

        assertThat(volatility[3]).isNull();
        assertThat(volatility[4]).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("负收益少于 2 个时不可估，保持缺失")
    void insufficientNegativeReturns() {
        Double[] volatility = DownsideVolatility.ofReturns(
                new double[] {100, 90, 99, 108.9, 118.8}, 4);

        assertThat(volatility[4]).isNull();
    }

    @Test
    @DisplayName("回看不足全为 null；period < 2 直接拒绝")
    void warmupAndValidation() {
        assertThat(DownsideVolatility.ofReturns(new double[] {10, 9, 8}, 5))
                .containsOnlyNulls();
        assertThatThrownBy(() -> DownsideVolatility.ofReturns(new double[] {10, 9}, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
