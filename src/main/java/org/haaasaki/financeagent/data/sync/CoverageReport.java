package org.haaasaki.financeagent.data.sync;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 全市场日K覆盖审计结果。
 *
 * @param latestTradeDate 全库最新交易日；空库时为 null
 * @param historyStart    本次审计采用的历史起点（最新交易日向前推 history-years）
 * @param all             全部在册标的的覆盖明细
 * @param gaps            覆盖不足、需要回填的标的（零行或历史前置缺口）
 * @param stale           首尾完整但近期无成交的标的（可能停牌，不参与回填）
 */
public record CoverageReport(
        LocalDate latestTradeDate,
        LocalDate historyStart,
        List<InstrumentCoverage> all,
        List<InstrumentCoverage> gaps,
        List<InstrumentCoverage> stale) {

    public static final CoverageReport EMPTY =
            new CoverageReport(null, null, List.of(), List.of(), List.of());

    public long totalInstruments() {
        return all.size();
    }

    public long gapInstruments() {
        return gaps.size();
    }

    public long staleInstruments() {
        return stale.size();
    }

    public long sufficientInstruments() {
        return all.size() - gaps.size() - stale.size();
    }

    public double coverageRatio() {
        return all.isEmpty() ? 1.0 : (double) sufficientInstruments() / all.size();
    }

    /** 缺口最大的前 {@code limit} 个标的，供审计日志展示。 */
    public List<InstrumentCoverage> topGaps(int limit) {
        return gaps.stream()
                .sorted(Comparator.comparingLong(InstrumentCoverage::missingBars).reversed())
                .limit(limit)
                .toList();
    }
}
