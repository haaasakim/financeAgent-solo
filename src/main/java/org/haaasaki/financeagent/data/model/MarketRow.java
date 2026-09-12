package org.haaasaki.financeagent.data.model;

/**
 * 市场列表读模型：标的档案 + 最新快照的投影。
 *
 * <p>这是查询侧的组合投影，不是可写实体——快照缺失时对应字段为 null。
 * 单位契约：价格=元、成交量=股、百分比=小数、市值=元。
 *
 * @param instrumentId 全局唯一标识
 * @param code         裸代码
 * @param name         证券简称
 * @param instrumentType {@code STOCK} / {@code ETF}
 * @param board        板块
 * @param lastPrice    最新价，元；无快照时为 null
 * @param pctChg       涨跌幅，小数；无快照时为 null
 * @param volume       成交量，股；无快照时为 null
 * @param amount       成交额，元；无快照时为 null
 * @param turnoverRate 换手率，小数；无快照时为 null
 * @param peTtm        市盈率 TTM，倍；亏损或无快照时为 null
 * @param pb           市净率，倍
 * @param totalMv      总市值，元
 * @param circMv       流通市值，元
 */
public record MarketRow(
        String instrumentId,
        String code,
        String name,
        String instrumentType,
        String board,
        Double lastPrice,
        Double pctChg,
        Long volume,
        Double amount,
        Double turnoverRate,
        Double peTtm,
        Double pb,
        Double totalMv,
        Double circMv) {
}
