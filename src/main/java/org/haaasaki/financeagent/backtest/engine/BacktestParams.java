package org.haaasaki.financeagent.backtest.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 回测参数（不可变）。全部参数写入 {@code backtest_task.params_json} 作为复现依据。
 *
 * @param strategyId     策略 id
 * @param strategyName   策略名快照
 * @param strategyHash   策略 DSL 规范化 JSON 的 SHA-256
 * @param startDate      回测开始交易日（含）
 * @param endDate        回测结束交易日（含）
 * @param frequency      调仓频率
 * @param topN           目标持仓数
 * @param initialCapital 初始资金，元
 * @param commissionRate 佣金率，小数（买卖双边）
 * @param minCommission  单笔最低佣金，元
 * @param stampTaxRate   印花税率，小数（仅卖出）
 * @param slippage       单边滑点率，小数
 * @param benchmarkId    基准 instrument_id，如 {@code 000300.SH}
 * @param dataThrough    运行开始时 daily_bar 最大交易日（数据快照标识）
 */
public record BacktestParams(
        long strategyId,
        String strategyName,
        String strategyHash,
        LocalDate startDate,
        LocalDate endDate,
        RebalanceFrequency frequency,
        int topN,
        BigDecimal initialCapital,
        BigDecimal commissionRate,
        BigDecimal minCommission,
        BigDecimal stampTaxRate,
        BigDecimal slippage,
        String benchmarkId,
        LocalDate dataThrough) {
}
