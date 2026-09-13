package org.haaasaki.financeagent.screener.factor;

/**
 * 因子值类型。
 *
 * <p>布尔因子在 DSL 中取 {@code 1}（是）/ {@code 0}（否），只允许 {@code eq}；
 * 数值因子按展示单位比较（如 % / 天 / 倍）。
 */
public enum FactorKind {
    NUMBER,
    BOOLEAN
}
