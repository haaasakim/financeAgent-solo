package org.haaasaki.financeagent.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 回测表单请求（页面原始值，费率以更易读的百分比 / bps 传入，服务层换算成小数）。
 *
 * @param strategyId       策略 id
 * @param startDate        开始交易日
 * @param endDate          结束交易日
 * @param frequency        调仓频率：DAILY / WEEKLY / MONTHLY / QUARTERLY
 * @param topN             目标持仓数
 * @param initialCapital   初始资金，元
 * @param commissionRatePct 佣金率，百分数（0.03 表示万 3）
 * @param stampTaxPct      印花税率，百分数（0.05 表示 0.05%），仅卖出
 * @param slippageBps      单边滑点，bps（5 表示 5bps）
 * @param benchmarkId      基准 instrument_id
 */
public record BacktestRequest(
        long strategyId,
        LocalDate startDate,
        LocalDate endDate,
        String frequency,
        int topN,
        BigDecimal initialCapital,
        BigDecimal commissionRatePct,
        BigDecimal stampTaxPct,
        BigDecimal slippageBps,
        String benchmarkId) {
}
