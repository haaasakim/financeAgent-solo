package org.haaasaki.financeagent.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.haaasaki.financeagent.data.model.BacktestTrade;

/**
 * 成交配对：把回测订单流水按标的配成「买入 → 卖出」往返记录。
 *
 * <p>一期整手整笔成交、无部分成交，且持仓期间不会再次买入，
 * 因此按 orderNo 顺序维护「一个未平仓买单」即可完成配对。
 * 仅 FILLED 订单参与配对；EXPIRED（停牌 / 一字涨跌停 / 现金不足 / 期末未成交）
 * 不产生往返记录，仍可在订单流水中查看。
 */
public final class TradePairing {

    private TradePairing() {
    }

    /**
     * @param trades       任务的全部成交订单，按 orderNo 升序
     * @param tradingDates 回测日度序列的交易日，升序
     * @return 往返记录，买入日升序、同日按代码升序
     */
    public static List<RoundTrip> pair(List<BacktestTrade> trades, List<LocalDate> tradingDates) {
        Map<LocalDate, Integer> dateIndex = new HashMap<>();
        for (int i = 0; i < tradingDates.size(); i++) {
            dateIndex.put(tradingDates.get(i), i);
        }
        LocalDate lastTradingDate = tradingDates.isEmpty()
                ? null
                : tradingDates.get(tradingDates.size() - 1);

        Map<String, BacktestTrade> openBuys = new LinkedHashMap<>();
        List<RoundTrip> result = new ArrayList<>();
        for (BacktestTrade trade : trades) {
            if (!BacktestTrade.STATUS_FILLED.equals(trade.status()) || trade.filledDate() == null) {
                continue;
            }
            if (BacktestTrade.SIDE_BUY.equals(trade.side())) {
                openBuys.put(trade.instrumentId(), trade);
            } else if (BacktestTrade.SIDE_SELL.equals(trade.side())) {
                BacktestTrade buy = openBuys.remove(trade.instrumentId());
                if (buy != null) {
                    result.add(closed(buy, trade,
                            holdingDays(dateIndex, buy.filledDate(), trade.filledDate())));
                }
            }
        }
        for (BacktestTrade buy : openBuys.values()) {
            result.add(open(buy, holdingDays(dateIndex, buy.filledDate(), lastTradingDate)));
        }
        result.sort(Comparator.comparing(RoundTrip::buyDate).thenComparing(RoundTrip::code));
        return List.copyOf(result);
    }

    private static RoundTrip closed(BacktestTrade buy, BacktestTrade sell, int holdingDays) {
        double buyAmount = amount(buy);
        double buyFee = fee(buy);
        double sellFee = fee(sell);
        double cost = buyAmount + buyFee;
        Double pnl = sell.pnl();
        Double returnPct = pnl == null || cost <= 0 ? null : pnl / cost;
        return new RoundTrip(buy.instrumentId(), buy.code(), buy.name(), buy.filledDate(),
                price(buy), shares(buy), buyAmount, buyFee,
                sell.filledDate(), sell.price(), sell.amount(), sellFee, buyFee + sellFee,
                pnl, returnPct, holdingDays);
    }

    private static RoundTrip open(BacktestTrade buy, int holdingDays) {
        double buyFee = fee(buy);
        return new RoundTrip(buy.instrumentId(), buy.code(), buy.name(), buy.filledDate(),
                price(buy), shares(buy), amount(buy), buyFee,
                null, null, null, null, buyFee, null, null, holdingDays);
    }

    /** 持有交易日数 = 日度序列下标差；日期缺失时保守返回 0。 */
    private static int holdingDays(Map<LocalDate, Integer> dateIndex,
                                   LocalDate buyDate, LocalDate sellDate) {
        Integer buy = dateIndex.get(buyDate);
        Integer sell = dateIndex.get(sellDate);
        if (buy == null || sell == null) {
            return 0;
        }
        return Math.max(0, sell - buy);
    }

    private static double price(BacktestTrade trade) {
        return trade.price() == null ? 0 : trade.price();
    }

    private static long shares(BacktestTrade trade) {
        return trade.shares() == null ? 0 : trade.shares();
    }

    private static double amount(BacktestTrade trade) {
        return trade.amount() == null ? 0 : trade.amount();
    }

    private static double fee(BacktestTrade trade) {
        return trade.fee() == null ? 0 : trade.fee();
    }
}
