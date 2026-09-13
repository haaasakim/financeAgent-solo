package org.haaasaki.financeagent.screener.template;

import java.util.List;

import org.haaasaki.financeagent.screener.dsl.CompareOp;
import org.haaasaki.financeagent.screener.dsl.Condition;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.springframework.stereotype.Component;

/**
 * 预设策略模板集合。
 *
 * <p>模板已按「当前有数据支撑的因子」适配：财务类模板（高质量趋势 / 红利防御）
 * 依赖财报数据源，待其落地后再补充。
 */
@Component
public class PresetTemplates {

    private final List<StrategyTemplate> templates;

    public PresetTemplates() {
        this.templates = List.of(
                new StrategyTemplate("lowValuation", "低估值价值",
                        "PE-TTM ≤ 30 且 PB ≤ 3",
                        new ScreenerDsl(
                                List.of(condition("peTtm", CompareOp.LTE, 30),
                                        condition("pb", CompareOp.LTE, 3)),
                                List.of(), List.of())),
                new StrategyTemplate("trendUp", "趋势向上",
                        "20 日收益 > 0、站上 MA60、MA20 > MA60",
                        new ScreenerDsl(
                                List.of(condition("return20", CompareOp.GT, 0),
                                        condition("aboveMa60", CompareOp.EQ, 1),
                                        condition("ma20GtMa60", CompareOp.EQ, 1)),
                                List.of(), List.of())),
                new StrategyTemplate("oversold", "超跌企稳",
                        "距 52 周高点 ≤ -30%、20 日波动率 ≤ 3%",
                        new ScreenerDsl(
                                List.of(condition("pctFrom52wHigh", CompareOp.LTE, -30),
                                        condition("volatility20", CompareOp.LTE, 3)),
                                List.of(), List.of())));
    }

    public List<StrategyTemplate> all() {
        return templates;
    }

    public StrategyTemplate find(String key) {
        return templates.stream()
                .filter(template -> template.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private Condition condition(String factor, CompareOp op, double value) {
        return new Condition(factor, op, value);
    }
}
