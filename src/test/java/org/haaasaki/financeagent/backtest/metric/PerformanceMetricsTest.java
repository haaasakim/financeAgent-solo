package org.haaasaki.financeagent.backtest.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 绩效指标测试：口径与边界（恒定净值、无平仓交易）。
 */
class PerformanceMetricsTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2025, 12, 31);

    @Test
    @DisplayName("累计收益 / 年化收益 / 最大回撤按净值序列计算")
    void computesFromNavSeries() {
        List<BacktestDaily> daily = List.of(
                row(1, 100_000, 0),
                row(2, 110_000, 0),
                row(3, 99_000, -0.10),
                row(4, 121_000, 0));

        BacktestMetrics metrics = PerformanceMetrics.compute(
                daily, new BigDecimal("100000"), List.of(), START, END);

        assertThat(metrics.finalNav()).isEqualTo(121_000);
        assertThat(metrics.cumulativeReturn()).isCloseTo(0.21, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metrics.maxDrawdown()).isCloseTo(-0.10, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metrics.annualReturn()).isNotNull();
        assertThat(metrics.winRate()).isNull();
    }

    @Test
    @DisplayName("恒定净值夏普为 null，空序列全部指标为 null")
    void handlesFlatAndEmpty() {
        BacktestMetrics flat = PerformanceMetrics.compute(
                List.of(row(1, 100_000, 0), row(2, 100_000, 0), row(3, 100_000, 0)),
                new BigDecimal("100000"), List.of(), START, END);
        assertThat(flat.sharpe()).isNull();
        assertThat(flat.cumulativeReturn()).isEqualTo(0);

        BacktestMetrics empty = PerformanceMetrics.compute(
                List.of(), new BigDecimal("100000"), List.of(), START, END);
        assertThat(empty.cumulativeReturn()).isNull();
        assertThat(empty.maxDrawdown()).isNull();
        assertThat(empty.turnover()).isNull();
    }

    @Test
    @DisplayName("胜率按已成交卖出单的净额盈亏统计，买入不计入")
    void winRateCountsFilledSellsOnly() {
        List<BacktestTrade> trades = List.of(
                trade("BUY", "FILLED", 0.0),
                trade("SELL", "FILLED", 120.0),
                trade("SELL", "FILLED", -80.0),
                trade("SELL", "EXPIRED", null));

        BacktestMetrics metrics = PerformanceMetrics.compute(
                List.of(row(1, 100_000, 0), row(2, 101_000, 0)),
                new BigDecimal("100000"), trades, START, END);

        assertThat(metrics.winRate()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    private BacktestDaily row(int day, double nav, double drawdown) {
        return new BacktestDaily(1, START.plusDays(day - 1L), nav, nav, 0, nav, drawdown);
    }

    private BacktestTrade trade(String side, String status, Double pnl) {
        return new BacktestTrade(1, 1, "000001.SZ", "000001", "测试", side, status,
                START, START.plusDays(1), START.plusDays(1),
                10.0, 100L, 1000.0, 5.0, pnl, null);
    }
}
