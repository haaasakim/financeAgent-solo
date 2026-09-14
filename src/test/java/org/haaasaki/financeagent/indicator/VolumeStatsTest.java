package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VolumeStatsTest {

    @Test
    @DisplayName("量比：分母不含当日，固定输入对拍")
    void ratioFixedFixture() {
        Double[] ratio = VolumeStats.ratio(new long[] {10, 20, 30, 40, 50, 60}, 2);

        assertThat(ratio[0]).isNull();
        assertThat(ratio[1]).isNull();
        assertThat(ratio[2]).isCloseTo(30.0 / 15.0, within(1e-12));
        assertThat(ratio[3]).isCloseTo(40.0 / 25.0, within(1e-12));
        assertThat(ratio[5]).isCloseTo(60.0 / 45.0, within(1e-12));
    }

    @Test
    @DisplayName("量比：分母为 0 时保持缺失（如长期停牌前后）")
    void ratioZeroDenominator() {
        Double[] ratio = VolumeStats.ratio(new long[] {0, 0, 10}, 2);

        assertThat(ratio[2]).isNull();
    }

    @Test
    @DisplayName("量能变化：5/20 日均量比，固定输入对拍")
    void changeFixedFixture() {
        long[] volumes = new long[20];
        for (int i = 0; i < 20; i++) {
            volumes[i] = i < 10 ? 10 : 20;
        }
        Double[] change = VolumeStats.change(volumes, 5, 20);

        assertThat(change[18]).isNull();
        assertThat(change[19]).isCloseTo(1.0 / 3.0, within(1e-12));
    }

    @Test
    @DisplayName("量能变化：全零成交量时为 null；短窗口大于长窗口直接拒绝")
    void changeEdgeCases() {
        Double[] change = VolumeStats.change(new long[] {0, 0, 0}, 2, 3);
        assertThat(change[2]).isNull();

        assertThatThrownBy(() -> VolumeStats.change(new long[] {1, 2}, 5, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VolumeStats.ratio(new long[] {1, 2}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
