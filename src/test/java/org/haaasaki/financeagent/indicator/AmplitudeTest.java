package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmplitudeTest {

    @Test
    @DisplayName("固定输入对拍：窗口内无前收盘价的 bar 跳过，其余取均值")
    void computesFixedFixture() {
        Double[] amplitude = Amplitude.mean(
                new double[] {10, 11, 12}, new double[] {9, 10, 11},
                new Double[] {null, 9.5, 10.5}, 3);

        assertThat(amplitude[0]).isNull();
        assertThat(amplitude[1]).isNull();
        assertThat(amplitude[2]).isCloseTo(
                ((11.0 - 10.0) / 9.5 + (12.0 - 11.0) / 10.5) / 2.0, within(1e-12));
    }

    @Test
    @DisplayName("窗口 1 用当日前收盘；无有效前收盘或窗口不足为 null")
    void edgeCases() {
        Double[] windowOne = Amplitude.mean(
                new double[] {10, 11}, new double[] {9, 10},
                new Double[] {null, 9.5}, 1);
        assertThat(windowOne[0]).isNull();
        assertThat(windowOne[1]).isCloseTo(1.0 / 9.5, within(1e-12));

        Double[] noPreClose = Amplitude.mean(
                new double[] {10, 11}, new double[] {9, 10},
                new Double[] {null, null}, 2);
        assertThat(noPreClose[1]).isNull();

        assertThat(Amplitude.mean(new double[] {10}, new double[] {9},
                new Double[] {null}, 5)).containsOnlyNulls();

        assertThatThrownBy(() -> Amplitude.mean(new double[] {10}, new double[] {9},
                new Double[] {null}, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
