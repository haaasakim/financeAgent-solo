package org.haaasaki.financeagent.screener.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单条选股条件。
 *
 * <p>评分字段（WI-011，全部可空）：
 * <ul>
 *   <li>{@code score}：{@code true} 参与评分、{@code false} 仅过滤；缺省表示兼容模式（WI-005 隐式口径）</li>
 *   <li>{@code weight}：评分权重 0.1~10，仅 {@code score=true} 时允许出现，缺省 1</li>
 *   <li>{@code direction}：评分方向；缺省由运算符推断，{@code eq} 必须显式指定</li>
 * </ul>
 *
 * @param factor    因子注册表 key（白名单）
 * @param op        比较运算符；布尔因子固定 {@code eq}
 * @param value     阈值：数值因子按展示单位（如 %）；布尔因子 {@code 1}=是 / {@code 0}=否
 * @param score     是否参与评分；null 表示未指定
 * @param weight    评分权重；null 表示 1
 * @param direction 评分方向；null 表示按运算符推断
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Condition(
        String factor,
        CompareOp op,
        Double value,
        Boolean score,
        Double weight,
        ScoreDirection direction) {

    /** 兼容旧调用：不携带评分字段，走 WI-005 隐式口径。 */
    public Condition(String factor, CompareOp op, Double value) {
        this(factor, op, value, null, null, null);
    }
}
