package org.haaasaki.financeagent.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.data.model.BacktestTrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成交配对测试：买入卖出配对、同一标的多次进出、期末持有与 EXPIRED 忽略。
 */
class TradePairingTest {

    private static final List<LocalDate> DAYS = List.of(
            LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 4),
            LocalDate.of(2024, 1, 5), LocalDate.of(2024, 2, 1));

    @Test
    @DisplayName("一次买入卖出：日期、价格、盈亏、收益率与持有交易日正确")
    void pairsClosedRoundTrip() {
        List<RoundTrip> trips = TradePairing.pair(List.of(
                trade(1, "000001.SZ", "000001", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 2), 10.0, 1000L, 10000.0, 5.0, null),
                trade(2, "000001.SZ", "000001", BacktestTrade.SIDE_SELL, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 5), 11.0, 1000L, 11000.0, 10.0, 985.0)
        ), DAYS);

        assertThat(trips).hasSize(1);
        RoundTrip trip = trips.get(0);
        assertThat(trip.instrumentId()).isEqualTo("000001.SZ");
        assertThat(trip.buyDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(trip.buyPrice()).isEqualTo(10.0);
        assertThat(trip.shares()).isEqualTo(1000L);
        assertThat(trip.sellDate()).isEqualTo(LocalDate.of(2024, 1, 5));
        assertThat(trip.sellPrice()).isEqualTo(11.0);
        assertThat(trip.buyFee()).isEqualTo(5.0);
        assertThat(trip.sellFee()).isEqualTo(10.0);
        assertThat(trip.feeTotal()).isEqualTo(15.0);
        assertThat(trip.pnl()).isEqualTo(985.0);
        assertThat(trip.holdingDays()).isEqualTo(3);
        // 收益率以买入成本（金额 + 费用）为分母
        assertThat(trip.returnPct()).isCloseTo(985.0 / 10005.0, within(1e-9));
    }

    @Test
    @DisplayName("同一标的两次往返：按买入时间各成一条")
    void pairsRepeatedRoundTrips() {
        List<RoundTrip> trips = TradePairing.pair(List.of(
                trade(1, "000001.SZ", "000001", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 2), 10.0, 1000L, 10000.0, 5.0, null),
                trade(2, "000001.SZ", "000001", BacktestTrade.SIDE_SELL, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 4), 11.0, 1000L, 11000.0, 10.0, 985.0),
                trade(3, "000001.SZ", "000001", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 2, 1), 12.0, 1000L, 12000.0, 5.0, null)
        ), DAYS);

        assertThat(trips).hasSize(2);
        assertThat(trips.get(0).buyDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(trips.get(0).sellDate()).isEqualTo(LocalDate.of(2024, 1, 4));
        assertThat(trips.get(1).buyDate()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(trips.get(1).sellDate()).isNull();
        assertThat(trips.get(1).pnl()).isNull();
        assertThat(trips.get(1).returnPct()).isNull();
    }

    @Test
    @DisplayName("期末持有：持有交易日计到最后一个交易日")
    void openPositionHoldingDaysToLastTradingDate() {
        List<RoundTrip> trips = TradePairing.pair(List.of(
                trade(1, "000002.SZ", "000002", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 2), 20.0, 500L, 10000.0, 5.0, null)
        ), DAYS);

        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).sellDate()).isNull();
        assertThat(trips.get(0).buyFee()).isEqualTo(5.0);
        assertThat(trips.get(0).sellFee()).isNull();
        assertThat(trips.get(0).feeTotal()).isEqualTo(5.0);
        assertThat(trips.get(0).holdingDays()).isEqualTo(4);
    }

    @Test
    @DisplayName("EXPIRED 订单不参与配对")
    void ignoresExpiredOrders() {
        List<RoundTrip> trips = TradePairing.pair(List.of(
                trade(1, "000001.SZ", "000001", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_EXPIRED,
                        LocalDate.of(2024, 1, 2), null, null, null, null, null),
                trade(2, "000001.SZ", "000001", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 3), 10.0, 1000L, 10000.0, 5.0, null),
                trade(3, "000001.SZ", "000001", BacktestTrade.SIDE_SELL, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 5), 11.0, 1000L, 11000.0, 10.0, 985.0)
        ), DAYS);

        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).buyDate()).isEqualTo(LocalDate.of(2024, 1, 3));
    }

    @Test
    @DisplayName("多标的同日买入：按代码升序")
    void sortsByBuyDateThenCode() {
        List<RoundTrip> trips = TradePairing.pair(List.of(
                trade(1, "000002.SZ", "000002", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 2), 20.0, 500L, 10000.0, 5.0, null),
                trade(2, "000001.SZ", "000001", BacktestTrade.SIDE_BUY, BacktestTrade.STATUS_FILLED,
                        LocalDate.of(2024, 1, 2), 10.0, 1000L, 10000.0, 5.0, null)
        ), DAYS);

        assertThat(trips).extracting(RoundTrip::code).containsExactly("000001", "000002");
    }

    private BacktestTrade trade(long orderNo, String instrumentId, String code, String side,
                                String status, LocalDate filledDate, Double price, Long shares,
                                Double amount, Double fee, Double pnl) {
        return new BacktestTrade(1L, orderNo, instrumentId, code, "测试标的", side, status,
                filledDate == null ? null : filledDate.minusDays(1), filledDate, filledDate,
                price, shares, amount, fee, pnl, null);
    }
}
