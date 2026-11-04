package org.haaasaki.financeagent.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

import org.haaasaki.financeagent.backtest.BacktestTaskFilter;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 回测记录筛选表单及 URL 参数。
 *
 * @param taskId 当前详情面板中的回测任务 id；空值表示未选择详情
 * @param strategyName 策略名称关键词；空值表示不筛选
 * @param frequency 调仓频率枚举值；空值表示不筛选
 * @param periodStart 回测区间开始日期，ISO 日期
 * @param periodEnd 回测区间结束日期，ISO 日期
 * @param cumulativeReturnMinPct 累计收益下限，百分数单位
 * @param cumulativeReturnMaxPct 累计收益上限，百分数单位
 * @param maxDrawdownMinPct 最大回撤下限，百分数单位
 * @param maxDrawdownMaxPct 最大回撤上限，百分数单位
 */
public record BacktestTaskFilterForm(
        Long taskId,
        String strategyName,
        String frequency,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
        BigDecimal cumulativeReturnMinPct,
        BigDecimal cumulativeReturnMaxPct,
        BigDecimal maxDrawdownMinPct,
        BigDecimal maxDrawdownMaxPct) {

    public BacktestTaskFilter toFilter() {
        return new BacktestTaskFilter(strategyName, frequency, periodStart, periodEnd,
                cumulativeReturnMinPct, cumulativeReturnMaxPct,
                maxDrawdownMinPct, maxDrawdownMaxPct);
    }

    public BacktestTaskFilterForm normalized() {
        String name = strategyName == null ? null : strategyName.trim();
        String normalizedName = name == null || name.isEmpty() ? null : name;
        String normalizedFrequency = frequency == null || frequency.isBlank()
                ? null : frequency.trim().toUpperCase(Locale.ROOT);
        return new BacktestTaskFilterForm(taskId, normalizedName, normalizedFrequency, periodStart, periodEnd,
                cumulativeReturnMinPct, cumulativeReturnMaxPct,
                maxDrawdownMinPct, maxDrawdownMaxPct);
    }
}
