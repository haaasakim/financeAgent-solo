package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AtrTest {

    private static final double[] HIGHS = {10.0, 11.0, 12.0};
    private static final double[] LOWS = {9.0, 10.0, 10.0};
    private static final double[] CLOSES = {9.5, 10.5, 11.0};
    private static final Double[] PRE_CLOSES = {null, 9.5, 10.5};

    @Test
    @DisplayName("固定输入对拍：首根缺失前收盘用 high-low，含跳空时取最大真实波幅")
    void computesFixedFixture() {
        Double[] ratio = Atr.ratio(HIGHS, LOWS, CLOSES, PRE_CLOSES, 2);

        assertThat(ratio[0]).isNull();
        // TR0 = 10-9 = 1；TR1 = max(1, |11-9.5|, |10-9.5|) = 1.5 → ATR = 1.25，/10.5
        assertThat(ratio[1]).isCloseTo(1.25 / 10.5, within(1e-12));
        // TR2 = max(2, |12-10.5|, |10-10.5|) = 2 → ATR = mean(1.5, 2) = 1.75，/11
        assertThat(ratio[2]).isCloseTo(1.75 / 11.0, within(1e-12));
    }

    @Test
    @DisplayName("收盘价 ≤ 0 时为 null；窗口不足为 null；period < 2 直接拒绝")
    void edgeCases() {
        Double[] zeroClose = Atr.ratio(new double[] {1, 1}, new double[] {0, 0},
                new double[] {0, 0}, new Double[] {null, null}, 2);
        assertThat(zeroClose[1]).isNull();

        Double[] insufficient = Atr.ratio(HIGHS, LOWS, CLOSES, PRE_CLOSES, 5);
        assertThat(insufficient).containsOnlyNulls();

        assertThatThrownBy(() -> Atr.ratio(HIGHS, LOWS, CLOSES, PRE_CLOSES, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
