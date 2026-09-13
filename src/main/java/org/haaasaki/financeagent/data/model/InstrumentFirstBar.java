package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 标的的首根日K日期，用于「上市不足 5 个交易日排除」判定。
 *
 * @param instrumentId 标的标识
 * @param firstDate    该标的日K的最小交易日
 */
public record InstrumentFirstBar(String instrumentId, LocalDate firstDate) {
}
