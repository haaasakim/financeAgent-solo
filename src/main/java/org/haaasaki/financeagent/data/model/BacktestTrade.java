package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 回测订单流水（{@code backtest_trade} 表）。
 *
 * <p>停牌与一字涨跌停导致的不能成交如实记录：首次计划成交日与最终状态分开存储，
 * 未成交为 {@code EXPIRED}，不伪造成交（见 docs/DATA_CONTRACT.md 红线）。
 * 一期为整手整笔撮合，无部分成交。
 *
 * @param taskId       回测任务 id
 * @param orderNo      任务内订单序号（从 1 开始）
 * @param instrumentId 标的标识
 * @param code         裸代码
 * @param name         标的名称
 * @param side         BUY / SELL
 * @param status       FILLED / EXPIRED
 * @param signalDate   信号日 T
 * @param orderDate    首次计划成交日 T+1
 * @param filledDate   实际成交日；未成交为 null
 * @param price        成交价（含滑点），元；未成交为 null
 * @param shares       股数（100 的整数倍）；未成交为 null
 * @param amount       成交金额，元；未成交为 null
 * @param fee          佣金 + 印花税合计，元；未成交为 null
 * @param pnl          已实现盈亏（卖出，扣双边费用）；买入与未成交为 null
 * @param reason       未成交原因：停牌 / 一字涨停 / 一字跌停 / 现金不足百股 / 期末未成交
 */
public record BacktestTrade(
        long taskId,
        long orderNo,
        String instrumentId,
        String code,
        String name,
        String side,
        String status,
        LocalDate signalDate,
        LocalDate orderDate,
        LocalDate filledDate,
        Double price,
        Long shares,
        Double amount,
        Double fee,
        Double pnl,
        String reason) {

    public static final String SIDE_BUY = "BUY";
    public static final String SIDE_SELL = "SELL";
    public static final String STATUS_FILLED = "FILLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String REASON_SUSPENDED = "停牌";
    public static final String REASON_LIMIT_UP = "一字涨停";
    public static final String REASON_LIMIT_DOWN = "一字跌停";
    public static final String REASON_CASH = "现金不足百股";
    public static final String REASON_CANCELLED = "调仓取消";
    public static final String REASON_END = "期末未成交";

    /** 前复权失真价格（WI-013）：失真窗口内不可交易。 */
    public static final String REASON_BAD_PRICE = "价格失真";

    /** FILLED 卖单的成交说明（WI-010 出场风控）。 */
    public static final String NOTE_STOP_LOSS = "止损卖出";
    public static final String NOTE_TIME_STOP = "时间止损";
}
