package org.haaasaki.financeagent.backtest.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A 股交易费用与滑点（回测口径）。
 *
 * <p>金额累加必须精确，全程 {@link BigDecimal}，保留 2 位小数（分）。
 * 佣金买卖双边、不足最低佣金按最低收；印花税仅卖出；滑点抬高买入价、压低卖出价。
 */
public final class TradingCost {

    /** 买卖双边佣金率，如 0.0003 = 万 3。 */
    private final BigDecimal commissionRate;
    /** 单笔最低佣金，元。 */
    private final BigDecimal minCommission;
    /** 卖出印花税率，如 0.0005 = 0.05%。 */
    private final BigDecimal stampTaxRate;
    /** 单边滑点率，如 0.0005 = 5bps。 */
    private final BigDecimal slippage;

    public TradingCost(BigDecimal commissionRate,
                       BigDecimal minCommission,
                       BigDecimal stampTaxRate,
                       BigDecimal slippage) {
        this.commissionRate = commissionRate;
        this.minCommission = minCommission;
        this.stampTaxRate = stampTaxRate;
        this.slippage = slippage;
    }

    /** 买入成交价 = 开盘价 × (1 + 滑点)，保留 3 位小数。 */
    public BigDecimal buyPrice(BigDecimal open) {
        return open.multiply(BigDecimal.ONE.add(slippage)).setScale(3, RoundingMode.HALF_UP);
    }

    /** 卖出成交价 = 开盘价 × (1 - 滑点)，保留 3 位小数。 */
    public BigDecimal sellPrice(BigDecimal open) {
        return open.multiply(BigDecimal.ONE.subtract(slippage)).setScale(3, RoundingMode.HALF_UP);
    }

    public BigDecimal buyFee(BigDecimal amount) {
        return commission(amount);
    }

    public BigDecimal sellFee(BigDecimal amount) {
        return commission(amount).add(stampTax(amount));
    }

    public BigDecimal commission(BigDecimal amount) {
        BigDecimal fee = amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        return fee.max(minCommission);
    }

    public BigDecimal stampTax(BigDecimal amount) {
        return amount.multiply(stampTaxRate).setScale(2, RoundingMode.HALF_UP);
    }
}
