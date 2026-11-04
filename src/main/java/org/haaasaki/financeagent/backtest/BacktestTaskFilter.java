package org.haaasaki.financeagent.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 回测记录筛选条件。
 *
 * @param strategyName 策略名称关键词，按不区分大小写的子串匹配；空值表示不筛选
 * @param frequency 调仓频率枚举值；空值表示不筛选
 * @param periodStart 回测区间下界；筛选任务开始日期不早于该日期
 * @param periodEnd 回测区间上界；筛选任务结束日期不晚于该日期
 * @param cumulativeReturnMinPct 累计收益下限，百分数单位；空值表示无下限
 * @param cumulativeReturnMaxPct 累计收益上限，百分数单位；空值表示无上限
 * @param maxDrawdownMinPct 最大回撤下限，百分数单位；空值表示无下限
 * @param maxDrawdownMaxPct 最大回撤上限，百分数单位；空值表示无上限
 */
public record BacktestTaskFilter(
        String strategyName,
        String frequency,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal cumulativeReturnMinPct,
        BigDecimal cumulativeReturnMaxPct,
        BigDecimal maxDrawdownMinPct,
        BigDecimal maxDrawdownMaxPct) {

    public static final BacktestTaskFilter EMPTY = new BacktestTaskFilter(
            null, null, null, null, null, null, null, null);

    /**
     * 去除名称两侧空格，并把全空条件归一化为不筛选。
     *
     * @return 可直接用于查询的规范化条件
     */
    public BacktestTaskFilter normalized() {
        String name = strategyName == null ? null : strategyName.trim();
        String normalizedName = name == null || name.isEmpty() ? null : name;
        String normalizedFrequency = frequency == null || frequency.isBlank()
                ? null : frequency.trim().toUpperCase(Locale.ROOT);
        return new BacktestTaskFilter(normalizedName, normalizedFrequency, periodStart, periodEnd,
                cumulativeReturnMinPct, cumulativeReturnMaxPct,
                maxDrawdownMinPct, maxDrawdownMaxPct);
    }
}
