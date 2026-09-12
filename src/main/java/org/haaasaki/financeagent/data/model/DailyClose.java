package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 收盘价投影，用于除权检测时比对重叠日期的价格。
 *
 * <p>单独定义而非复用 {@link DailyBar}：除权检测只需要日期与收盘价两列，
 * 拉全字段会白白增加 IO 与映射开销。
 *
 * @param tradeDate 交易日
 * @param close     收盘价，元（前复权口径）
 */
public record DailyClose(LocalDate tradeDate, double close) {
}
