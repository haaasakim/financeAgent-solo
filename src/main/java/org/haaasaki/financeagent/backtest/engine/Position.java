package org.haaasaki.financeagent.backtest.engine;

import java.math.BigDecimal;

/**
 * 单个持仓：股数与含费成本（买入金额 + 买入佣金）。
 *
 * <p>只增不减地整笔买入与整笔卖出，不做部分成交，因此成本按总额维护。
 */
final class Position {

    private final String instrumentId;
    private final String code;
    private final String name;
    private long shares;
    private BigDecimal cost;

    Position(String instrumentId, String code, String name, long shares, BigDecimal cost) {
        this.instrumentId = instrumentId;
        this.code = code;
        this.name = name;
        this.shares = shares;
        this.cost = cost;
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
}
