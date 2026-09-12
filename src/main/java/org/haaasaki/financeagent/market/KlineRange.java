package org.haaasaki.financeagent.market;

import java.time.LocalDate;

/**
 * 个股详情页的 K 线区间。
 *
 * @param key     URL 参数值
 * @param label   页面按钮文案
 * @param months  回看月数；{@link #ALL} 为 0，表示从最早数据开始
 */
public enum KlineRange {

    THREE_MONTHS("3m", "近 3 月", 3),
    SIX_MONTHS("6m", "近 6 月", 6),
    ONE_YEAR("1y", "近 1 年", 12),
    THREE_YEARS("3y", "近 3 年", 36),
    ALL("all", "全部", 0);

    /** 全部区间的查询起点。10 年日K最早不早于此，足够。 */
    private static final LocalDate EARLIEST = LocalDate.of(1990, 1, 1);

    private final String key;
    private final String label;
    private final int months;

    KlineRange(String key, String label, int months) {
        this.key = key;
        this.label = label;
        this.months = months;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public LocalDate startDate(LocalDate today) {
        return months == 0 ? EARLIEST : today.minusMonths(months);
    }

    /** 未知键回退到近 6 月，避免链接失效时报错。 */
    public static KlineRange from(String key) {
        if (key != null) {
            for (KlineRange range : values()) {
                if (range.key.equalsIgnoreCase(key)) {
                    return range;
                }
            }
        }
        return SIX_MONTHS;
    }
}
