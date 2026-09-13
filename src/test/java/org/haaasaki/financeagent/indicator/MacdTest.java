package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MacdTest {

    @Test
    @DisplayName("横盘序列的 DIF / DEA / HIST 全为 0")
    void flatSeriesIsZero() {
        MacdResult result = Macd.calculate(new double[] {10, 10, 10});

        assertThat(result.dif()).containsExactly(0.0, 0.0, 0.0);
        assertThat(result.dea()).containsExactly(0.0, 0.0, 0.0);
        assertThat(result.hist()).containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("固定输入对拍：首根播种的 EMA 递推结果")
    void computesFixedFixture() {
        // 手算（Python 独立复算）：closes = [10, 11, 12]
        MacdResult result = Macd.calculate(new double[] {10, 11, 12});

        assertThat(result.dif()[0]).isCloseTo(0.0, within(1e-9));
        assertThat(result.dif()[1]).isCloseTo(0.0797720797720789, within(1e-12));
        assertThat(result.dif()[2]).isCloseTo(0.22113456871291426, within(1e-12));

        assertThat(result.dea()[1]).isCloseTo(0.01595441595441578, within(1e-12));
        assertThat(result.dea()[2]).isCloseTo(0.05699044650611548, within(1e-12));

        assertThat(result.hist()[1]).isCloseTo(0.06381766381766311, within(1e-12));
        assertThat(result.hist()[2]).isCloseTo(0.16414412220679878, within(1e-12));
    }

    @Test
    @DisplayName("HIST 恒等于 DIF - DEA，且不乘 2")
    void histIsDifMinusDea() {
        MacdResult result = Macd.calculate(new double[] {10, 12, 11, 13, 12.5});

        for (int i = 0; i < result.dif().length; i++) {
            assertThat(result.hist()[i]).isCloseTo(result.dif()[i] - result.dea()[i], within(1e-12));
        }
    }

    @Test
    @DisplayName("空序列返回三个空数组")
    void emptyInput() {
        MacdResult result = Macd.calculate(new double[] {});

        assertThat(result.dif()).isEmpty();
        assertThat(result.dea()).isEmpty();
        assertThat(result.hist()).isEmpty();
    }
}
