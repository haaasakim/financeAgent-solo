package org.haaasaki.financeagent.data.model;

/**
 * 已解析为 SQL 片段的条件（仅供 Mapper 使用）。
 *
 * <p>安全约定：{@code expression} 与 {@code sqlOp} 只能由服务端白名单
 * （因子注册表 + 运算符枚举）生成，绝不来自用户原始字符串；{@code value} 走参数绑定。
 *
 * @param expression 白名单 SQL 表达式，如 {@code (f.return_20 * 100)}
 * @param sqlOp      白名单运算符符号，如 {@code >=}
 * @param value      参数化绑定的阈值
 */
public record SqlCondition(
        String expression,
        String sqlOp,
        Double value) {
}
