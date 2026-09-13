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
 * 日K缓存：按标的惰性加载区间日K，提供「当日 bar」与「最近收盘价」两种读取。
 *
 * <ul>
 *   <li>{@link #barOn}：精确当日行情，停牌返回 null（撮合用）</li>
 *   <li>{@link #closeAt}：最近一个可交易日收盘价，停牌期间盯市沿用（估值用）</li>
 * </ul>
 */
final class PriceBook {

    private final DailyBarMapper dailyBarMapper;
    private final LocalDate from;
    private final LocalDate to;
    private final Map<String, NavigableMap<LocalDate, DailyBar>> cache = new HashMap<>();

    PriceBook(DailyBarMapper dailyBarMapper, LocalDate from, LocalDate to) {
        this.dailyBarMapper = dailyBarMapper;
        this.from = from;
        this.to = to;
    }

    DailyBar barOn(String instrumentId, LocalDate date) {
        return map(instrumentId).get(date);
    }

    Double closeAt(String instrumentId, LocalDate date) {
        Map.Entry<LocalDate, DailyBar> entry = map(instrumentId).floorEntry(date);
        return entry == null ? null : entry.getValue().close();
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
