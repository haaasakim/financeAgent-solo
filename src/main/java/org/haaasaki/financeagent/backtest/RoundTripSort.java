package org.haaasaki.financeagent.backtest;

import java.util.Comparator;

/**
 * 回测往返记录排序字段白名单。
 *
 * <p>请求里的排序键只会映射到本枚举，不接受用户原始字符串；
 * 排序在服务层内存完成（单任务往返记录数量有限）。
 * 期末持有（{@code sellDate == null}）的记录在可空列上始终排在最后。
 */
public enum RoundTripSort {

    CODE("code", "代码", false),
    NAME("name", "名称", false),
    BUY_DATE("buyDate", "买入日", false),
    BUY_PRICE("buyPrice", "买入价", true),
    BUY_AMOUNT("buyAmount", "买入金额", true),
    BUY_FEE("buyFee", "买入费用", true),
    SELL_DATE("sellDate", "卖出日", false),
    SELL_PRICE("sellPrice", "卖出价", true),
    SELL_FEE("sellFee", "卖出费用", true),
    SHARES("shares", "股数", true),
    HOLDING_DAYS("holdingDays", "持有（交易日）", true),
    PNL("pnl", "盈亏", true),
    RETURN_PCT("returnPct", "收益率", true);

    /** 按买入日升序、同日按代码升序，与配对输出顺序一致。 */
    public static final RoundTripSort DEFAULT = BUY_DATE;

    private final String key;
    private final String label;
    private final boolean numeric;

    RoundTripSort(String key, String label, boolean numeric) {
        this.key = key;
        this.label = label;
        this.numeric = numeric;
    }

    /** URL 参数值。 */
    public String key() {
        return key;
    }

    /** 表头展示名。 */
    public String label() {
        return label;
    }

    /** 数值列右对齐，点击后默认降序。 */
    public boolean numeric() {
        return numeric;
    }

    /**
     * 排序比较器：指定方向；同值按买入日、代码升序兜底，保证分页稳定。
     * 可空列（卖出日 / 卖出价 / 盈亏 / 收益率）空值恒在最后，不随方向翻转。
     */
    public Comparator<RoundTrip> comparator(boolean desc) {
        Comparator<RoundTrip> primary = (a, b) -> {
            int result = compareField(a, b);
            if (result != 0 && desc && !hasNull(a, b)) {
                return -result;
            }
            return result;
        };
        return primary.thenComparing(RoundTrip::buyDate).thenComparing(RoundTrip::code);
    }

    private int compareField(RoundTrip a, RoundTrip b) {
        return switch (this) {
            case CODE -> a.code().compareTo(b.code());
            case NAME -> a.name().compareTo(b.name());
            case BUY_DATE -> a.buyDate().compareTo(b.buyDate());
            case BUY_PRICE -> Double.compare(a.buyPrice(), b.buyPrice());
            case BUY_AMOUNT -> Double.compare(a.buyAmount(), b.buyAmount());
            case BUY_FEE -> Double.compare(a.buyFee(), b.buyFee());
            case SELL_DATE -> compareNullable(a.sellDate(), b.sellDate());
            case SELL_PRICE -> compareNullable(a.sellPrice(), b.sellPrice());
            case SELL_FEE -> compareNullable(a.sellFee(), b.sellFee());
            case SHARES -> Long.compare(a.shares(), b.shares());
            case HOLDING_DAYS -> Integer.compare(a.holdingDays(), b.holdingDays());
            case PNL -> compareNullable(a.pnl(), b.pnl());
            case RETURN_PCT -> compareNullable(a.returnPct(), b.returnPct());
        };
    }

    /** 字段在当前两条记录中是否有空值（空值不参与方向翻转）。 */
    private boolean hasNull(RoundTrip a, RoundTrip b) {
        return switch (this) {
            case SELL_DATE -> a.sellDate() == null || b.sellDate() == null;
            case SELL_PRICE -> a.sellPrice() == null || b.sellPrice() == null;
            case SELL_FEE -> a.sellFee() == null || b.sellFee() == null;
            case PNL -> a.pnl() == null || b.pnl() == null;
            case RETURN_PCT -> a.returnPct() == null || b.returnPct() == null;
            default -> false;
        };
    }

    /** 空值视为最大，恒排在最后。 */
    private static <T extends Comparable<T>> int compareNullable(T a, T b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }

    /** 未知键回退到默认（买入日升序），保证链接可手工修改而不报错。 */
    public static RoundTripSort fromKey(String key) {
        if (key != null) {
            for (RoundTripSort field : values()) {
                if (field.key.equalsIgnoreCase(key)) {
                    return field;
                }
            }
        }
        return DEFAULT;
    }
}
