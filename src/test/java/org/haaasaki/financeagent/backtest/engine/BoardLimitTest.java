package org.haaasaki.financeagent.backtest.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 涨跌停幅度与一字板判定测试（不依赖 Spring）。
 */
class BoardLimitTest {

    @Test
    @DisplayName("幅度按板块 × 风险警示取值")
    void ratioByBoardAndRiskWarning() {
        assertThat(BoardLimit.ratio("MAIN", false)).isEqualTo(0.10);
        assertThat(BoardLimit.ratio("GEM", false)).isEqualTo(0.20);
        assertThat(BoardLimit.ratio("STAR", false)).isEqualTo(0.20);
        assertThat(BoardLimit.ratio("BSE", false)).isEqualTo(0.30);
        assertThat(BoardLimit.ratio("ETF", false)).isEqualTo(0.10);

        assertThat(BoardLimit.ratio("MAIN", true)).isEqualTo(0.05);
        assertThat(BoardLimit.ratio("GEM", true)).isEqualTo(0.20);
        assertThat(BoardLimit.ratio("STAR", true)).isEqualTo(0.20);
        assertThat(BoardLimit.ratio("BSE", true)).isEqualTo(0.30);
    }

    @Test
    @DisplayName("涨跌停价按前收盘 2 位小数取整，容差内视为触及")
    void limitPrices() {
        assertThat(BoardLimit.limitUp(10.00, 0.10)).isEqualTo(11.00);
        assertThat(BoardLimit.limitDown(10.00, 0.10)).isEqualTo(9.00);
        assertThat(BoardLimit.limitUp(11.11, 0.10)).isEqualTo(12.22);

        assertThat(BoardLimit.atLimit(11.00, 11.00)).isTrue();
        assertThat(BoardLimit.atLimit(11.004, 11.00)).isTrue();
        assertThat(BoardLimit.atLimit(11.02, 11.00)).isFalse();
    }
}
