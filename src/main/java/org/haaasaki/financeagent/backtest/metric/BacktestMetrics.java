package org.haaasaki.financeagent.backtest.metric;

/**
 * 绩效指标快照。口径见 docs/work-items/WI-006-回测模块.md 第 2.3 节。
 *
 * @param finalNav         最终净值，元
 * @param cumulativeReturn 累计收益，小数
 * @param annualReturn     年化收益，小数；区间为空时为 null
 * @param maxDrawdown      最大回撤，负数小数；区间为空时为 null
 * @param sharpe           夏普比率（无风险利率取 0）；样本不足或波动为 0 时为 null
 * @param winRate          已平仓交易胜率，小数；无平仓交易时为 null
 * @param turnover         年化换手率，小数；平均净值为 0 时为 null
 */
public record BacktestMetrics(
        double finalNav,
        Double cumulativeReturn,
        Double annualReturn,
        Double maxDrawdown,
        Double sharpe,
        Double winRate,
        Double turnover) {
}
