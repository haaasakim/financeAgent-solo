package org.haaasaki.financeagent.data.sync;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.InstrumentBarStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 日K覆盖审计与缺口回填。
 *
 * <p>背景：{@link MarketDataSyncService#syncMissingDailyBars()} 只识别「零行」标的。
 * 一旦某个标的被增量同步写入过几天数据，它就不会再被补历史，沪市大量标的因此长期只有
 * 最近几根日K。本服务改为按「应有交易日数 vs 实际根数」判定，缺口标的整段重拉。
 *
 * <p>应有交易日数 = 全库出现过的交易日中，落在
 * {@code [max(上市日, 历史起点), 最新交易日]} 内的数量。上市日缺失时从历史起点算，
 * 因此首次回填会把上市日回填为实际首根日K，后续审计就不会再误判。
 *
 * <p>是否充分不只看根数，而看「首尾是否覆盖完整区间」：停牌股在停牌期间本来就没有日K，
 * 用固定比例会把正常停牌误判为缺口、反复重拉。因此首根晚于区间起点（超过宽限）或
 * 末根早于最新交易日（超过宽限）才判为缺口；区间内部的空洞视为停牌。
 *
 * <p>回填只做 upsert，不删除既有行；失败时保留旧数据，可重复执行。
 */
@Service
public class DataCoverageService {

    private static final Logger log = LoggerFactory.getLogger(DataCoverageService.class);

    private static final int TOP_GAP_SAMPLE = 10;

    private final InstrumentMapper instrumentMapper;
    private final DailyBarMapper dailyBarMapper;
    private final MarketDataSyncService syncService;
    private final int historyYears;
    private final int startGraceDays;
    private final int staleDays;

    public DataCoverageService(InstrumentMapper instrumentMapper,
                               DailyBarMapper dailyBarMapper,
                               MarketDataSyncService syncService,
                               @Value("${finance.sync.history-years:10}") int historyYears,
                               @Value("${finance.sync.coverage-start-grace-days:15}") int startGraceDays,
                               @Value("${finance.sync.coverage-stale-days:30}") int staleDays) {
        this.instrumentMapper = instrumentMapper;
        this.dailyBarMapper = dailyBarMapper;
        this.syncService = syncService;
        this.historyYears = historyYears;
        this.startGraceDays = startGraceDays;
        this.staleDays = staleDays;
    }

    /** 只读审计：不写库、不发外部请求。 */
    public CoverageReport audit() {
        List<LocalDate> marketDates = dailyBarMapper.findAllTradeDates();
        if (marketDates.isEmpty()) {
            log.warn("覆盖审计：daily_bar 为空，无法计算应有交易日");
            return CoverageReport.EMPTY;
        }
        LocalDate latest = marketDates.get(marketDates.size() - 1);
        LocalDate historyStart = latest.minusYears(historyYears);
        Map<String, InstrumentBarStats> stats = dailyBarMapper.findInstrumentBarStats().stream()
                .collect(Collectors.toMap(InstrumentBarStats::instrumentId, stat -> stat));

        List<InstrumentCoverage> all = new ArrayList<>();
        for (Instrument instrument : instrumentMapper.findAllListed()) {
            InstrumentBarStats stat = stats.get(instrument.instrumentId());
            long actual = stat == null ? 0 : stat.barCount();
            LocalDate first = stat == null ? null : stat.firstTradeDate();
            LocalDate last = stat == null ? null : stat.lastTradeDate();
            LocalDate expectedStart = instrument.listedAt() == null
                    ? historyStart
                    : max(instrument.listedAt(), historyStart);
            long expected = countBetween(marketDates, expectedStart, latest);
            CoverageState state = classify(expected, actual, first, last, expectedStart, latest);
            all.add(new InstrumentCoverage(instrument, expected, actual, first, last,
                    state == CoverageState.SUFFICIENT, state == CoverageState.STALE));
        }

        List<InstrumentCoverage> gaps = all.stream()
                .filter(coverage -> !coverage.sufficient() && !coverage.stale())
                .toList();
        List<InstrumentCoverage> stale = all.stream()
                .filter(InstrumentCoverage::stale)
                .toList();
        CoverageReport report = new CoverageReport(latest, historyStart, all, gaps, stale);
        logReport(report);
        return report;
    }

    /**
     * 按覆盖审计结果回填历史日K。
     *
     * @param limit 本轮最多处理的标的数；{@code <= 0} 表示不限
     * @return 回填任务结果；消息中附带回填后的剩余缺口
     */
    public SyncResult backfill(int limit) {
        CoverageReport before = audit();
        List<Instrument> targets = before.gaps().stream()
                .map(InstrumentCoverage::instrument)
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
        if (targets.isEmpty()) {
            log.info("历史回填：没有需要回填的标的");
            return new SyncResult("BACKFILL_DAILY_BARS", "SUCCESS", 0, 0, 0, "无覆盖缺口");
        }

        log.info("历史回填：本轮处理 {} 只 / 缺口共 {} 只", targets.size(), before.gapInstruments());
        SyncResult result = syncService.backfillDailyBars(targets);

        CoverageReport after = audit();
        String remaining = after.gapInstruments() == 0
                ? "；回填后已无覆盖缺口"
                : "；回填后仍有 " + after.gapInstruments() + " 只缺口，可重复执行 --sync.backfill 继续补齐";
        if (after.staleInstruments() > 0) {
            remaining += "；另有 " + after.staleInstruments() + " 只近期无成交（可能停牌），不参与回填";
        }
        String prefix = result.message() == null || result.message().isBlank()
                ? "" : result.message();
        String message = prefix.isEmpty() ? remaining.substring(1) : prefix + remaining;
        log.info("历史回填结束：{}", remaining);
        return new SyncResult(result.taskName(), result.status(), result.total(),
                result.success(), result.fail(), message);
    }

    /**
     * 覆盖状态分类。
     *
     * <p>停牌期间本来就没有日K，若按固定比例判定，会把正常停牌股反复判为缺口。
     * 规则：
     * <ul>
     *   <li>无应有数据（上市日晚于最新交易日）→ 充分</li>
     *   <li>零行、首根晚于区间起点超过宽限 → 历史缺口，需要回填</li>
     *   <li>首根正常但末根早于最新交易日超过宽限 → 近期无成交（可能停牌），不回填</li>
     *   <li>其余（区间内部有空洞）→ 充分</li>
     * </ul>
     */
    private CoverageState classify(long expected, long actual,
                                   LocalDate first, LocalDate last,
                                   LocalDate expectedStart, LocalDate latest) {
        if (expected <= 0) {
            return CoverageState.SUFFICIENT;
        }
        if (actual <= 0 || first == null || last == null) {
            return CoverageState.GAP;
        }
        boolean startsOnTime = !first.isAfter(expectedStart.plusDays(startGraceDays));
        if (!startsOnTime) {
            return CoverageState.GAP;
        }
        boolean current = !last.isBefore(latest.minusDays(staleDays));
        return current ? CoverageState.SUFFICIENT : CoverageState.STALE;
    }

    private enum CoverageState {
        SUFFICIENT,
        GAP,
        STALE
    }

    private LocalDate max(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    /** 落在 [from, to] 内的交易日数量；dates 必须升序。 */
    private long countBetween(List<LocalDate> dates, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return 0;
        }
        return Math.max(0, upperBound(dates, to) - lowerBound(dates, from));
    }

    private int lowerBound(List<LocalDate> dates, LocalDate target) {
        int low = 0;
        int high = dates.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (dates.get(mid).isBefore(target)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int upperBound(List<LocalDate> dates, LocalDate target) {
        int low = 0;
        int high = dates.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (dates.get(mid).isAfter(target)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private void logReport(CoverageReport report) {
        log.info("覆盖审计：最新交易日 {}，历史起点 {}，标的 {}，充分 {}（{}%），缺口 {}，近期无成交 {}",
                report.latestTradeDate(), report.historyStart(), report.totalInstruments(),
                report.sufficientInstruments(),
                String.format(Locale.ROOT, "%.1f", report.coverageRatio() * 100),
                report.gapInstruments(), report.staleInstruments());

        Map<String, List<InstrumentCoverage>> byExchange = new LinkedHashMap<>();
        for (String exchange : List.of("SH", "SZ", "BJ")) {
            byExchange.put(exchange, new ArrayList<>());
        }
        for (InstrumentCoverage coverage : report.all()) {
            byExchange.computeIfAbsent(coverage.instrument().exchange(), key -> new ArrayList<>())
                    .add(coverage);
        }
        byExchange.forEach((exchange, rows) -> {
            if (rows.isEmpty()) {
                return;
            }
            long gaps = rows.stream()
                    .filter(row -> !row.sufficient() && !row.stale())
                    .count();
            long stale = rows.stream().filter(InstrumentCoverage::stale).count();
            log.info("  {}：{} 只，缺口 {}，近期无成交 {}", exchange, rows.size(), gaps, stale);
        });

        for (InstrumentCoverage gap : report.topGaps(TOP_GAP_SAMPLE)) {
            log.info("  缺口 {} {}：{}/{} 根（{}%）", gap.instrument().instrumentId(),
                    gap.instrument().name(), gap.actualBars(), gap.expectedBars(),
                    String.format(Locale.ROOT, "%.1f", gap.coverageRatio() * 100));
        }
        report.stale().stream().limit(TOP_GAP_SAMPLE).forEach(stale ->
                log.info("  近期无成交 {} {}：最后交易日 {}（可能停牌）",
                        stale.instrument().instrumentId(), stale.instrument().name(),
                        stale.lastTradeDate()));
    }
}
