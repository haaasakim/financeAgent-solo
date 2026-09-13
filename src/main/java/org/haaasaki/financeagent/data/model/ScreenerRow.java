package org.haaasaki.financeagent.data.model;

/**
 * 选股结果行（读模型投影）。
 *
 * <p>{@code score} 由服务层在命中集内计算，SQL 投影恒为 NULL；
 * 口径见 docs/work-items/WI-005-选股评分与排序.md 第 2 节。
 *
 * @param instrumentId 标的 ID
 * @param code         裸代码
 * @param name         名称
 * @param lastPrice    最新价，元；无快照为 null
 * @param pctChg       涨跌幅，小数；无快照为 null
 * @param peTtm        市盈率 TTM，倍；亏损或无快照为 null
 * @param pb           市净率，倍
 * @param return20     20 日收益，小数；因子缺失为 null
 * @param score        评分 0~100，一位小数近似；无参与条件为 null
 */
public record ScreenerRow(
        String instrumentId,
        String code,
        String name,
        Double lastPrice,
        Double pctChg,
        Double peTtm,
        Double pb,
        Double return20,
        Double score) {

    public ScreenerRow withScore(Double newScore) {
        return new ScreenerRow(instrumentId, code, name, lastPrice, pctChg,
                peTtm, pb, return20, newScore);
    }
}
