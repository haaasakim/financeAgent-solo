package org.haaasaki.financeagent.backtest.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.haaasaki.financeagent.data.model.BacktestTrade;

/**
 * 引擎内部订单：终态与成交明细都记录在这里，最后统一转成 {@link BacktestTrade} 落库。
 *
 * <p>未成交不是失败：延后只更新计划成交日与原因；回测结束仍未成交记为 EXPIRED，
 * 不伪造成交（见 docs/DATA_CONTRACT.md 红线）。
 */
final class Order {

    private final long orderNo;
    private final String instrumentId;
    private final String code;
    private final String name;
    private final String side;
    private final LocalDate signalDate;
    /** 买单预算 = 信号日组合净值 / topN（固定 N 槽等权，WI-009）；卖单为 null。 */
    private final BigDecimal budget;
    /** 成交说明（如「止损卖出」/「时间止损」），仅风控卖单非空（WI-010）。 */
    private final String note;
    private LocalDate orderDate;

    private String status;
    private LocalDate filledDate;
    private Double price;
    private Long shares;
    private Double amount;
    private Double fee;
    private Double pnl;
    private String reason;

    Order(long orderNo, String instrumentId, String code, String name,
          String side, LocalDate signalDate, LocalDate orderDate, BigDecimal budget,
          String note) {
        this.orderNo = orderNo;
        this.instrumentId = instrumentId;
        this.code = code;
        this.name = name;
        this.side = side;
        this.signalDate = signalDate;
        this.orderDate = orderDate;
        this.budget = budget;
        this.note = note;
    }

    void fill(LocalDate date, double price, long shares,
              double amount, double fee, Double pnl) {
        this.status = BacktestTrade.STATUS_FILLED;
        this.filledDate = date;
        this.price = price;
        this.shares = shares;
        this.amount = amount;
        this.fee = fee;
        this.pnl = pnl;
        this.reason = note;
    }

    void expire(String reason) {
        this.status = BacktestTrade.STATUS_EXPIRED;
        this.reason = reason;
    }

    void defer(LocalDate nextDate, String reason) {
        this.orderDate = nextDate;
        this.reason = reason;
    }

    boolean isPending() {
        return status == null;
    }

    BacktestTrade toTrade(long taskId) {
        return new BacktestTrade(taskId, orderNo, instrumentId, code, name, side, status,
                signalDate, orderDate, filledDate, price, shares, amount, fee, pnl, reason);
    }

    long orderNo() {
        return orderNo;
    }

    String instrumentId() {
        return instrumentId;
    }

    String side() {
        return side;
    }

    LocalDate orderDate() {
        return orderDate;
    }

    BigDecimal budget() {
        return budget;
    }

    /** 风控卖单（止损 / 时间止损）：调仓不得取消，标的进入禁买集合。 */
    boolean isStop() {
        return note != null;
    }

    String reason() {
        return reason;
    }

    String code() {
        return code;
    }

    String name() {
        return name;
    }
}
