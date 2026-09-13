package org.haaasaki.financeagent.screener.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单条选股条件。
 *
 * @param factor 因子注册表 key（白名单）
 * @param op     比较运算符；布尔因子固定 {@code eq}
 * @param value  阈值：数值因子按展示单位（如 %）；布尔因子 {@code 1}=是 / {@code 0}=否
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Condition(
        String factor,
        CompareOp op,
        Double value) {
}
