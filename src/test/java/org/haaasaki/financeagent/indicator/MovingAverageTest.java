package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MovingAverageTest {

    @Test
    @DisplayName("固定输入对拍：窗口 3 的前两格回看不足为 null")
    void computesFixedFixture() {
        assertThat(MovingAverage.sma(new double[] {1, 2, 3, 4, 5}, 3))
                .containsExactly(null, null, 2.0, 3.0, 4.0);
    }

    @Test
    @DisplayName("窗口 1 等于原序列")
    void windowOfOneIsIdentity() {
        assertThat(MovingAverage.sma(new double[] {7.5, 8.25}, 1))
                .containsExactly(7.5, 8.25);
    }

    @Test
    @DisplayName("空序列返回空数组")
    void emptyInput() {
        assertThat(MovingAverage.sma(new double[] {}, 5)).isEmpty();
    }

    @Test
    @DisplayName("序列短于窗口时全为 null")
    void shorterThanWindow() {
        assertThat(MovingAverage.sma(new double[] {1, 2}, 5)).containsExactly(null, null);
    }

    @Test
    @DisplayName("非法窗口直接拒绝")
    void rejectsInvalidWindow() {
        assertThatThrownBy(() -> MovingAverage.sma(new double[] {1}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
