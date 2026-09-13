package org.haaasaki.financeagent.backtest.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 费用与滑点测试：最低佣金、卖出印花税、滑点方向。
 */
class TradingCostTest {

    private final TradingCost cost = new TradingCost(
            new BigDecimal("0.0003"), new BigDecimal("5"),
            new BigDecimal("0.0005"), new BigDecimal("0.0005"));

    @Test
    @DisplayName("佣金不足 5 元按 5 元收，买卖双边")
    void commissionHasMinimum() {
        assertThat(cost.commission(new BigDecimal("10000.00")))
                .isEqualByComparingTo("5.00");
        assertThat(cost.commission(new BigDecimal("100000.00")))
                .isEqualByComparingTo("30.00");
        assertThat(cost.buyFee(new BigDecimal("10000.00"))).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("印花税只在卖出收取")
    void stampTaxOnlyOnSell() {
        BigDecimal amount = new BigDecimal("100000.00");
        assertThat(cost.sellFee(amount)).isEqualByComparingTo("80.00");
        assertThat(cost.buyFee(amount)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("滑点抬高买入价、压低卖出价")
    void slippageDirection() {
        assertThat(cost.buyPrice(new BigDecimal("10.00"))).isEqualByComparingTo("10.005");
        assertThat(cost.sellPrice(new BigDecimal("10.00"))).isEqualByComparingTo("9.995");
    }
}
