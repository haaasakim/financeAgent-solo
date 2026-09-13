package org.haaasaki.financeagent.backtest.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组合账户：现金与持仓，资金累加全程 {@link BigDecimal}。
 */
final class Portfolio {

    private BigDecimal cash;
    private final Map<String, Position> positions = new LinkedHashMap<>();

    Portfolio(BigDecimal cash) {
        this.cash = cash;
    }

    BigDecimal cash() {
        return cash;
    }

    Map<String, Position> positions() {
        return positions;
    }

    boolean holds(String instrumentId) {
        return positions.containsKey(instrumentId);
    }

    void add(String instrumentId, String code, String name, long shares, BigDecimal cost) {
        positions.put(instrumentId, new Position(instrumentId, code, name, shares, cost));
    }

    Position remove(String instrumentId) {
        return positions.remove(instrumentId);
    }

    void addCash(BigDecimal amount) {
        cash = cash.add(amount);
    }

    void subtractCash(BigDecimal amount) {
        cash = cash.subtract(amount);
    }
}
