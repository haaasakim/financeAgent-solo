package org.haaasaki.financeagent.screener.factor;

/**
 * 因子定义（注册表条目）。
 *
 * <p>{@code expression} 是服务端白名单维护的「最新截面」SQL 表达式，引用固定别名：
 * {@code i}=instrument、{@code ms}=market_snapshot、{@code f}=factor_daily 最新行。
 * {@code backtestExpression} 是回测用的历史截面表达式，引用 {@code i} 与
 * {@code f}=信号日所在行（调用方以 {@code f.trade_date = asOf} 连接）；为 null 表示该因子
 * 无历史序列、不可用于回测（如快照估值因子）。用户输入只提供 {@link #key()}，
 * 禁止把用户字符串拼进表达式。
 *
 * @param key                稳定标识，DSL 使用
 * @param group              目录分组：基础 / 估值 / 质量 / 成长 / 趋势 / 风险
 * @param label              页面展示名
 * @param kind               值类型
 * @param expression         最新截面 SQL 表达式；{@code available=false} 时为 null
 * @param backtestExpression 历史截面 SQL 表达式；null 表示不可回测
 * @param unit               展示单位（% / 天 / 倍 / 空）
 * @param available          是否有数据支撑；false 表示财务因子占位，页面灰显不可选
 */
public record FactorDefinition(
        String key,
        String group,
        String label,
        FactorKind kind,
        String expression,
        String backtestExpression,
        String unit,
        boolean available) {

    /** 是否有历史序列可用于回测。 */
    public boolean backtestable() {
        return backtestExpression != null;
    }
}
