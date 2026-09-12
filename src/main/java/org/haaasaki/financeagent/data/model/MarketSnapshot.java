package org.haaasaki.financeagent.data.model;

/**
 * 全市场最新快照（{@code market_snapshot} 表）。
 *
 * <p>每个标的保留最新一条，{@code fetchedAt} 即该行数据的可用时点（available_at）。
 * 单位契约：价格=元、成交量=股、百分比=小数、市值=元。
 *
 * @param instrumentId 全局唯一标识，如 {@code 600000.SH}
 * @param lastPrice    最新价，元；停牌或无成交时为 null（不填 0）
 * @param pctChg       涨跌幅，小数（0.0125 = 1.25%）；无法计算时为 null
 * @param volume       成交量，股
 * @param amount       成交额，元
 * @param turnoverRate 换手率，小数
 * @param peTtm        市盈率 TTM，倍；亏损时为 null
 * @param pb           市净率，倍
 * @param totalMv      总市值，元
 * @param circMv       流通市值，元
 * @param source       来源标识，如 {@code SINA_MARKET_CENTER}
 * @param fetchedAt    抓取时刻（ISO 本地时间），该行数据的 available_at
 */
public record MarketSnapshot(
        String instrumentId,
        Double lastPrice,
        Double pctChg,
        Long volume,
        Double amount,
        Double turnoverRate,
        Double peTtm,
        Double pb,
        Double totalMv,
        Double circMv,
        String source,
        String fetchedAt) {
}
