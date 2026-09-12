package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 本地数据库状态快照，用于应用启动时打印摘要与首页总览。
 *
 * <p>纯只读统计，不参与业务逻辑。
 *
 * @param instrumentTotal     标的字典总数（股票 + ETF）
 * @param stockTotal          股票数
 * @param etfTotal            ETF 数
 * @param dailyBarTotal       日K总行数
 * @param coveredInstruments  已有日K数据的标的数（与标的总数的比值即「覆盖率」）
 * @param minTradeDate        全库最早的交易日；无数据时为 null
 * @param maxTradeDate        全库最晚的交易日；无数据时为 null
 * @param lastSyncTask        最近一次同步的任务名；从未同步过时为 null
 * @param lastSyncStatus      最近一次同步的状态
 * @param lastSyncFinishedAt  最近一次同步的结束时间，ISO 文本
 */
public record DataStatistics(
        long instrumentTotal,
        long stockTotal,
        long etfTotal,
        long dailyBarTotal,
        long coveredInstruments,
        LocalDate minTradeDate,
        LocalDate maxTradeDate,
        String lastSyncTask,
        String lastSyncStatus,
        String lastSyncFinishedAt) {

    public boolean isEmpty() {
        return instrumentTotal == 0;
    }
}
