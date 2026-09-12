package org.haaasaki.financeagent.market;

/**
 * 市场概览条中的单个指数行情。
 *
 * @param instrumentId 指数 ID，如 {@code 000001.SH}
 * @param name         展示名称（来自配置，保证源站改名时展示稳定）
 * @param close        最新收盘点位；无数据时为 null
 * @param change       较前一交易日涨跌点数；无数据时为 null
 * @param pctChg       涨跌幅，小数；无数据时为 null
 */
public record IndexQuote(
        String instrumentId,
        String name,
        Double close,
        Double change,
        Double pctChg) {
}
