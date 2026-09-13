package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VolatilityTest {

    @Test
    @DisplayName("固定输入对拍：样本标准差（分母 n-1）")
    void computesFixedFixture() {
        // closes = [100, 110, 99] → 收益率 [0.1, -0.1]，样本标准差 = sqrt(0.02)
        Double[] volatility = Volatility.ofReturns(new double[] {100, 110, 99}, 2);

        assertThat(volatility[0]).isNull();
        assertThat(volatility[1]).isNull();
        assertThat(volatility[2]).isCloseTo(Math.sqrt(0.02), within(1e-12));
    }

    @Test
    @DisplayName("横盘收益率为 0，波动率为 0（不是 null）")
    void flatSeriesIsZero() {
        Double[] volatility = Volatility.ofReturns(new double[] {5, 5, 5}, 2);

        assertThat(volatility[2]).isCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("少于 period + 1 根收盘价时全为 null")
    void insufficientWarmup() {
        assertThat(Volatility.ofReturns(new double[] {1, 2}, 2))
                .containsExactly(null, null);
        assertThat(Volatility.ofReturns(new double[] {}, 20)).isEmpty();
    }

    @Test
    @DisplayName("period 小于 2 直接拒绝（样本标准差无意义）")
    void rejectsInvalidPeriod() {
        assertThatThrownBy(() -> Volatility.ofReturns(new double[] {1, 2}, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
