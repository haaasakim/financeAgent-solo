package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 日频历史估值（{@code valuation_daily} 表）。
 *
 * <p>来源为东财数据中心，按交易日收盘后可用的假设使用（见 docs/work-items/WI-007-历史估值数据源.md）。
 * 缺失语义：无数据为 null；亏损等无效值不落库，**禁止用 0 填充**。
 *
 * @param instrumentId 标的标识（股票）
 * @param tradeDate    交易日
 * @param peTtm        市盈率 TTM，倍；非正视为无效，存 null
 * @param pbMrq        市净率 MRQ，倍；非正视为无效，存 null
 * @param source       来源标识，如 {@code EASTMONEY_DATACENTER}
 */
public record ValuationDaily(
        String instrumentId,
        LocalDate tradeDate,
        Double peTtm,
        Double pbMrq,
        String source) {
}
