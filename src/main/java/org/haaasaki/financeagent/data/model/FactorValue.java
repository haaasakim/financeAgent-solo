package org.haaasaki.financeagent.data.model;

/**
 * 因子取值（服务层评分用读模型）。
 *
 * <p>按标的取因子注册表白名单表达式的结果；无数据为 null，不填 0。
 *
 * @param instrumentId 全局唯一标识
 * @param value        因子值，单位与库内口径一致；无数据为 null
 */
public record FactorValue(
        String instrumentId,
        Double value) {
}
