package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 回测日度序列（{@code backtest_daily} 表）。
 *
 * <p>资金类数值由引擎内 {@code BigDecimal} 结算后转为 double 落库，仅用于展示与曲线；
 * 盈亏、换手等绩效口径见 docs/work-items/WI-006-回测模块.md 第 2.3 节。
 *
 * @param taskId       回测任务 id
 * @param tradeDate    交易日
 * @param nav          组合资产总值（现金 + 持仓市值），元
 * @param cash         现金，元
 * @param marketValue  持仓市值，元
 * @param benchmarkNav 基准净值，元；首日归一为初始资金
 * @param drawdown     相对历史峰值回撤，负数小数
 */
public record BacktestDaily(
        long taskId,
        LocalDate tradeDate,
        double nav,
        double cash,
        double marketValue,
        double benchmarkNav,
        double drawdown) {
}
