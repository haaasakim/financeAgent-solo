package org.haaasaki.financeagent.data.provider;

import java.time.LocalDate;

/**
 * 单条历史估值点（Provider 原始返回值，未关联 instrument_id）。
 *
 * @param code      裸代码，如 {@code 600000}
 * @param tradeDate 交易日
 * @param peTtm     市盈率 TTM，倍；无效为 null
 * @param pbMrq     市净率 MRQ，倍；无效为 null
 */
public record ValuationPoint(
        String code,
        LocalDate tradeDate,
        Double peTtm,
        Double pbMrq) {
}
