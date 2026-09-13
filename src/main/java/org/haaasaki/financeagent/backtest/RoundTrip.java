package org.haaasaki.financeagent.backtest;

import java.time.LocalDate;

/**
 * 单笔「买入 → 卖出」往返记录（视图模型，不落库）。
 *
 * <p>由 {@link TradePairing} 从成交订单配对得出：同一标的多次进出时，
 * 每次买入与其后的卖出各成一条。期末仍未卖出的记录 {@code sellDate} 为 null，
 * 表示仍持有，盈亏与收益率不计算（页面显示「持有中」）。
 *
 * @param instrumentId 标的标识
 * @param code         裸代码
 * @param name         标的名称
 * @param buyDate      买入成交日
 * @param buyPrice     买入成交价（含滑点），元
 * @param shares       股数
 * @param buyAmount    买入金额（不含费用），元
 * @param sellDate     卖出成交日；未卖出为 null
 * @param sellPrice    卖出成交价（含滑点），元；未卖出为 null
 * @param sellAmount   卖出金额（不含费用），元；未卖出为 null
 * @param pnl          已实现盈亏（卖出，扣双边费用），元；未卖出为 null
 * @param returnPct    收益率 = 盈亏 / 买入成本（买入金额 + 费用）；未卖出为 null
 * @param holdingDays  持有交易日数（按回测日度序列计）
 */
public record RoundTrip(
        String instrumentId,
        String code,
        String name,
        LocalDate buyDate,
        double buyPrice,
        long shares,
        double buyAmount,
        LocalDate sellDate,
        Double sellPrice,
        Double sellAmount,
        Double pnl,
        Double returnPct,
        int holdingDays) {
}
