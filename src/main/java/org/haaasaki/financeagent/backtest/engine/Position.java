package org.haaasaki.financeagent.backtest.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单个持仓：股数与含费成本（买入金额 + 买入佣金）。
 *
 * <p>只增不减地整笔买入与整笔卖出，不做部分成交，因此成本按总额维护。
 * 买入成交日用于时间止损的持有交易日计数（WI-010）。
 */
final class Position {

    private final String instrumentId;
    private final String code;
    private final String name;
    private final LocalDate entryDate;
    private long shares;
    private BigDecimal cost;

    Position(String instrumentId, String code, String name, long shares, BigDecimal cost,
             LocalDate entryDate) {
        this.instrumentId = instrumentId;
        this.code = code;
        this.name = name;
        this.shares = shares;
        this.cost = cost;
        this.entryDate = entryDate;
    }

    String instrumentId() {
        return instrumentId;
    }

    String code() {
        return code;
    }

    String name() {
        return name;
    }

    long shares() {
        return shares;
    }

    BigDecimal cost() {
        return cost;
    }

    LocalDate entryDate() {
        return entryDate;
    }
}
