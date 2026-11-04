package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 单个标的的日K条数与日期范围，供覆盖审计使用。
 *
 * @param instrumentId   统一标的标识，如 {@code 600000.SH}
 * @param barCount       已落库的日K条数
 * @param firstTradeDate 最早交易日；无数据时为 null
 * @param lastTradeDate  最晚交易日；无数据时为 null
 */
public record InstrumentBarStats(
        String instrumentId,
        long barCount,
        LocalDate firstTradeDate,
        LocalDate lastTradeDate) {
}
