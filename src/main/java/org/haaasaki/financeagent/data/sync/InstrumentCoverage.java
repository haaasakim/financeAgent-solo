package org.haaasaki.financeagent.data.sync;

import java.time.LocalDate;

import org.haaasaki.financeagent.data.model.Instrument;

/**
 * 单个标的的日K覆盖情况。
 *
 * @param instrument     标的档案
 * @param expectedBars   按上市日（缺失时按历史起点）到最新交易日应有多少根日K
 * @param actualBars     实际落库根数
 * @param firstTradeDate 已落库的最早交易日；无数据时为 null
 * @param lastTradeDate  已落库的最晚交易日；无数据时为 null
 * @param sufficient     是否覆盖了完整的历史区间
 * @param stale          首尾覆盖完整但近期无成交（可能停牌），不属于可回填的历史缺口
 */
public record InstrumentCoverage(
        Instrument instrument,
        long expectedBars,
        long actualBars,
        LocalDate firstTradeDate,
        LocalDate lastTradeDate,
        boolean sufficient,
        boolean stale) {

    public long missingBars() {
        return Math.max(0, expectedBars - actualBars);
    }

    public double coverageRatio() {
        if (expectedBars <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) actualBars / expectedBars);
    }
}
