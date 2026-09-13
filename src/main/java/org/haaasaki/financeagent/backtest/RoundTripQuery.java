package org.haaasaki.financeagent.backtest;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 「按标的配对」搜索条件：代码/名称关键词 + 买入日、卖出日范围（闭区间）。
 *
 * <p>空白关键词与空日期视为不过滤。期末持有（{@code sellDate == null}）
 * 在设置了卖出日条件时不匹配。过滤在服务层内存完成（单任务往返记录数量有限）。
 *
 * @param keyword  代码或名称模糊匹配，空白视为不过滤
 * @param buyFrom  买入日下限（含）；null 不过滤
 * @param buyTo    买入日上限（含）；null 不过滤
 * @param sellFrom 卖出日下限（含）；null 不过滤；期末持有不匹配
 * @param sellTo   卖出日上限（含）；null 不过滤；期末持有不匹配
 */
public record RoundTripQuery(
        String keyword,
        LocalDate buyFrom,
        LocalDate buyTo,
        LocalDate sellFrom,
        LocalDate sellTo) {

    public static final RoundTripQuery EMPTY =
            new RoundTripQuery(null, null, null, null, null);

    public RoundTripQuery {
        keyword = blankToNull(keyword);
    }

    public boolean isEmpty() {
        return keyword == null && buyFrom == null && buyTo == null
                && sellFrom == null && sellTo == null;
    }

    public boolean matches(RoundTrip trip) {
        return matchesKeyword(trip) && inBuyRange(trip) && inSellRange(trip);
    }

    private boolean matchesKeyword(RoundTrip trip) {
        if (keyword == null) {
            return true;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        return trip.code().toLowerCase(Locale.ROOT).contains(needle)
                || trip.name().toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean inBuyRange(RoundTrip trip) {
        if (buyFrom != null && trip.buyDate().isBefore(buyFrom)) {
            return false;
        }
        return buyTo == null || !trip.buyDate().isAfter(buyTo);
    }

    private boolean inSellRange(RoundTrip trip) {
        if (sellFrom == null && sellTo == null) {
            return true;
        }
        if (trip.sellDate() == null) {
            return false;
        }
        if (sellFrom != null && trip.sellDate().isBefore(sellFrom)) {
            return false;
        }
        return sellTo == null || !trip.sellDate().isAfter(sellTo);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
