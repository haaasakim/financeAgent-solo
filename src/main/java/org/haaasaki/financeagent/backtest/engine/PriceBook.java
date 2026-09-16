package org.haaasaki.financeagent.backtest.engine;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.model.DailyBar;

/**
 * 日K缓存：按标的惰性加载区间日K，提供「当日 bar」与「最近有效收盘价」两种读取。
 *
 * <ul>
 *   <li>{@link #barOn}：精确当日行情，停牌返回 null（撮合用）</li>
 *   <li>{@link #closeAt}：最近一个有效收盘价（跳过缺失与非正价格，停牌期间盯市沿用）</li>
 * </ul>
 *
 * <p>前复权失真防护（WI-013）：腾讯前复权为「减现金分红」的加性口径，
 * 高分红标的历史价格会趋零甚至为负。失真 bar（任一 OHLC 或 pre_close ≤ 0）
 * 所在标的**整段不可交易**，由 {@link #tradableAt} 判定。
 */
final class PriceBook {

    /** 盯市价向前回退的最大根数（约 52 周）。 */
    static final int CLOSE_LOOKBACK_BARS = 250;

    private final DailyBarMapper dailyBarMapper;
    private final LocalDate from;
    private final LocalDate to;
    private final Map<String, NavigableMap<LocalDate, DailyBar>> cache = new HashMap<>();
    private final Map<String, List<LocalDate[]>> untradableCache = new HashMap<>();

    PriceBook(DailyBarMapper dailyBarMapper, LocalDate from, LocalDate to) {
        this.dailyBarMapper = dailyBarMapper;
        this.from = from;
        this.to = to;
    }

    DailyBar barOn(String instrumentId, LocalDate date) {
        return map(instrumentId).get(date);
    }

    /** 最近一个有效（>0）收盘价，向前有界回退；无可用价为 null。 */
    Double closeAt(String instrumentId, LocalDate date) {
        Map.Entry<LocalDate, DailyBar> entry = map(instrumentId).floorEntry(date);
        int looked = 0;
        while (entry != null && looked++ <= CLOSE_LOOKBACK_BARS) {
            Double close = entry.getValue().close();
            if (close != null && close > 0) {
                return close;
            }
            entry = map(instrumentId).lowerEntry(entry.getKey());
        }
        return null;
    }

    /** 当日 bar 是否可用于成交：存在且全部价格字段为正（pre_close 可缺失）。 */
    boolean usableBar(DailyBar bar) {
        if (bar == null) {
            return false;
        }
        if (bar.open() <= 0 || bar.high() <= 0 || bar.low() <= 0 || bar.close() <= 0) {
            return false;
        }
        return bar.preClose() == null || bar.preClose() > 0;
    }

    /**
     * 该标的在给定日期是否可交易（当日 bar 的可用性由调用方另行判断）。
     *
     * <p>规则：区间内出现过失真 bar 的标的**整段不可交易**。失真段的边界无法用局部窗口可靠圈定：
     * 前复权失真在首次非正价之前同样存在（价格被累计分红压低但恰为正，如 000408.SZ
     * 2019-05-14 的 0.227 元），若允许成交，会在失真恢复后按真实价格卖出产生虚假收益。
     */
    boolean tradableAt(String instrumentId, LocalDate date) {
        for (LocalDate[] window : untradableCache.computeIfAbsent(instrumentId,
                id -> untradableWindows(map(id)))) {
            if (!date.isBefore(window[0]) && date.isBefore(window[1])) {
                return false;
            }
        }
        return true;
    }

    /** 有失真 bar 则整段不可交易；无失真返回空集。 */
    private List<LocalDate[]> untradableWindows(NavigableMap<LocalDate, DailyBar> bars) {
        for (DailyBar bar : bars.values()) {
            if (!usableBar(bar)) {
                return List.<LocalDate[]>of(new LocalDate[] {LocalDate.MIN, LocalDate.MAX});
            }
        }
        return List.of();
    }

    /** 一次性加载基准区间行情，返回「交易日 → 收盘价」。 */
    NavigableMap<LocalDate, Double> loadCloses(String instrumentId) {
        NavigableMap<LocalDate, Double> closes = new TreeMap<>();
        for (DailyBar bar : dailyBarMapper.findRange(instrumentId, from, to)) {
            closes.put(bar.tradeDate(), bar.close());
        }
        return closes;
    }

    private NavigableMap<LocalDate, DailyBar> map(String instrumentId) {
        return cache.computeIfAbsent(instrumentId, id -> {
            NavigableMap<LocalDate, DailyBar> bars = new TreeMap<>();
            List<DailyBar> range = dailyBarMapper.findRange(id, from, to);
            for (DailyBar bar : range) {
                bars.put(bar.tradeDate(), bar);
            }
            return bars;
        });
    }
}
