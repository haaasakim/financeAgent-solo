package org.haaasaki.financeagent.data.sync;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.SyncLogMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.InstrumentSnapshot;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.provider.SnapshotProvider;
import org.haaasaki.financeagent.data.support.BoardClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 行情数据同步服务。
 *
 * <p>三个入口：标的字典同步、日K全量初始化、日K增量更新。
 *
 * <p><b>除权检测</b>是本服务的核心逻辑。前复权价在每次除权后会整体重算，
 * 若只做增量追加，历史数据会与新数据处于不同复权基准上。因此增量同步时：
 * <pre>
 * 拉最近 30 天 → 与库中重叠日期的 close 比对
 *   ├─ 全部一致 → 仅 upsert 增量部分
 *   └─ 存在不一致 → 判定除权 → 从「库中最早日期」全量重拉并覆盖
 * </pre>
 * 重拉区间取自库中最早日期而非固定年限，确保覆盖范围 ⊇ 已存数据，
 * 这样无需先删数据即可通过 upsert 完成整段覆写，避免「删了却拉不回来」的窗口。
 */
@Service
public class MarketDataSyncService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSyncService.class);

    /** 除权检测容差：价格精确到分，差异超过 1 厘即视为复权基准已变化。 */
    private static final double CLOSE_EPSILON = 0.001;

    /** 增量回看窗口（自然日）。upsert 幂等，多拉无害。 */
    private static final int INCREMENTAL_LOOKBACK_DAYS = 30;

    /** 进度日志间隔：全量 7,200 只标的时约每 500 只打一条，既能看进度又不刷屏。 */
    private static final int PROGRESS_INTERVAL = 500;

    private final InstrumentMapper instrumentMapper;
    private final DailyBarMapper dailyBarMapper;
    private final SyncLogMapper syncLogMapper;
    private final QuoteProvider quoteProvider;
    private final SnapshotProvider snapshotProvider;
    private final int historyYears;
    private final int batchSize;

    public MarketDataSyncService(
            InstrumentMapper instrumentMapper,
            DailyBarMapper dailyBarMapper,
            SyncLogMapper syncLogMapper,
            QuoteProvider quoteProvider,
            SnapshotProvider snapshotProvider,
            @Value("${finance.sync.history-years:10}") int historyYears,
            @Value("${finance.sync.batch-size:500}") int batchSize) {
        this.instrumentMapper = instrumentMapper;
        this.dailyBarMapper = dailyBarMapper;
        this.syncLogMapper = syncLogMapper;
        this.quoteProvider = quoteProvider;
        this.snapshotProvider = snapshotProvider;
        this.historyYears = historyYears;
        this.batchSize = batchSize;
    }

    /** 同步全市场标的字典（股票 + ETF）。 */
    public SyncResult syncInstruments() {
        LocalDateTime startedAt = LocalDateTime.now();
        String taskName = "SYNC_INSTRUMENTS";
        try {
            List<Instrument> instruments = new ArrayList<>();
            instruments.addAll(toInstruments(snapshotProvider.fetchStocks(), Instrument.TYPE_STOCK));
            instruments.addAll(toInstruments(snapshotProvider.fetchEtfs(), Instrument.TYPE_ETF));

            int written = 0;
            for (List<Instrument> batch : partition(instruments, batchSize)) {
                written += instrumentMapper.upsertBatch(batch);
            }

            String message = "股票 + ETF 共 " + written + " 只";
            recordSyncLog(taskName, snapshotProvider.name(), "SUCCESS",
                    instruments.size(), written, 0, message, startedAt);
            log.info("标的字典同步完成：{}", message);
            return new SyncResult(taskName, "SUCCESS", instruments.size(), written, 0, message);
        } catch (RuntimeException e) {
            recordSyncLog(taskName, snapshotProvider.name(), "FAILED", 0, 0, 0, e.getMessage(), startedAt);
            throw e;
        }
    }

    /**
     * 全量初始化日K历史。
     *
     * @param limit 最多处理的标的数量，&lt;= 0 表示不限制。用于小样本验证。
     */
    public SyncResult initializeDailyBars(int limit) {
        LocalDateTime startedAt = LocalDateTime.now();
        String taskName = "INIT_DAILY_BARS";
        List<Instrument> targets = instrumentMapper.findAllListed();
        if (limit > 0 && targets.size() > limit) {
            targets = targets.subList(0, limit);
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(historyYears);

        int success = 0;
        int fail = 0;
        int processed = 0;
        List<String> errors = new ArrayList<>();

        for (Instrument instrument : targets) {
            processed++;
            try {
                List<DailyBar> bars = quoteProvider.fetchDailyBars(instrument.instrumentId(), start, end);
                if (bars.isEmpty()) {
                    fail++;
                    errors.add(instrument.instrumentId() + ": 区间内无数据");
                } else {
                    for (List<DailyBar> batch : partition(bars, batchSize)) {
                        dailyBarMapper.upsertBatch(batch);
                    }
                    // 用最早交易日近似上市日期（首次为 null 时才回填）
                    instrumentMapper.updateListedAt(instrument.instrumentId(), bars.get(0).tradeDate());
                    success++;
                }
            } catch (DataIntegrityException e) {
                fail++;
                errors.add(instrument.instrumentId() + ": 数据校验失败 - " + e.getMessage());
                log.warn("数据校验失败 {} - {}", instrument.instrumentId(), e.getMessage());
            } catch (DataSyncException e) {
                fail++;
                errors.add(instrument.instrumentId() + ": 同步失败 - " + e.getMessage());
                log.warn("同步失败 {} - {}", instrument.instrumentId(), e.getMessage());
            }
            if (processed % PROGRESS_INTERVAL == 0) {
                log.info("日K初始化进度 {}/{}（成功 {}，失败 {}）", processed, targets.size(), success, fail);
            }
        }

        String status = fail == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL");
        String message = summarize(errors);
        recordSyncLog(taskName, quoteProvider.name(), status, targets.size(), success, fail, message, startedAt);
        log.info("日K初始化完成：总计 {}，成功 {}，失败 {}", targets.size(), success, fail);
        return new SyncResult(taskName, status, targets.size(), success, fail, message);
    }

    /** 增量更新日K，含除权检测。 */
    public SyncResult syncDailyIncremental() {
        LocalDateTime startedAt = LocalDateTime.now();
        String taskName = "INCR_DAILY_BARS";
        List<Instrument> targets = instrumentMapper.findAllListed();

        LocalDate end = LocalDate.now();
        LocalDate windowStart = end.minusDays(INCREMENTAL_LOOKBACK_DAYS);

        int success = 0;
        int revised = 0;
        int fail = 0;
        int processed = 0;
        List<String> errors = new ArrayList<>();

        for (Instrument instrument : targets) {
            processed++;
            String id = instrument.instrumentId();
            try {
                List<DailyBar> recent = quoteProvider.fetchDailyBars(id, windowStart, end);
                if (!recent.isEmpty()) {
                    if (isAdjustmentRevised(id, recent)) {
                        log.info("检测到复权基准变化，全量重拉 {}", id);
                        reviseAll(id, end);
                        revised++;
                    } else {
                        dailyBarMapper.upsertBatch(recent);
                    }
                    success++;
                }
            } catch (DataIntegrityException e) {
                fail++;
                errors.add(id + ": 数据校验失败 - " + e.getMessage());
                log.warn("数据校验失败 {} - {}", id, e.getMessage());
            } catch (DataSyncException e) {
                fail++;
                errors.add(id + ": 同步失败 - " + e.getMessage());
                log.warn("同步失败 {} - {}", id, e.getMessage());
            }
            if (processed % PROGRESS_INTERVAL == 0) {
                log.info("日K增量进度 {}/{}（成功 {}，除权重拉 {}，失败 {}）",
                        processed, targets.size(), success, revised, fail);
            }
        }

        String status = fail == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL");
        String message = "成功 " + success + "，其中除权重拉 " + revised + "；失败 " + fail
                + (errors.isEmpty() ? "" : "；" + summarize(errors));
        recordSyncLog(taskName, quoteProvider.name(), status, targets.size(), success, fail, message, startedAt);
        log.info("日K增量完成：{}", message);
        return new SyncResult(taskName, status, targets.size(), success, fail, message);
    }

    /** 与库中重叠日期的收盘价逐条比对，任一不一致即判定发生除权。 */
    private boolean isAdjustmentRevised(String instrumentId, List<DailyBar> recent) {
        LocalDate from = recent.get(0).tradeDate();
        LocalDate to = recent.get(recent.size() - 1).tradeDate();
        Map<LocalDate, Double> existing = dailyBarMapper.findCloseMap(instrumentId, from, to);
        if (existing.isEmpty()) {
            return false;
        }
        for (DailyBar bar : recent) {
            Double old = existing.get(bar.tradeDate());
            if (old != null && Math.abs(old - bar.close()) > CLOSE_EPSILON) {
                log.debug("复权基准不一致 {} {} 库中={} 新={}", instrumentId, bar.tradeDate(), old, bar.close());
                return true;
            }
        }
        return false;
    }

    /**
     * 全量重拉覆盖。
     *
     * <p>起点取「库中最早日期」，保证重拉范围覆盖已存数据，从而可以直接 upsert 覆写，
     * 无需先删除——拉取失败时不会留下数据空洞。
     */
    private void reviseAll(String instrumentId, LocalDate end) {
        LocalDate start = dailyBarMapper.findMinTradeDate(instrumentId)
                .orElse(end.minusYears(historyYears));
        List<DailyBar> all = quoteProvider.fetchDailyBars(instrumentId, start, end);
        if (all.isEmpty()) {
            throw new DataSyncException("除权重拉失败，返回空数据: " + instrumentId);
        }
        for (List<DailyBar> batch : partition(all, batchSize)) {
            dailyBarMapper.upsertBatch(batch);
        }
    }

    private void recordSyncLog(String taskName, String provider, String status,
                               int total, int success, int fail, String message, LocalDateTime startedAt) {
        syncLogMapper.insert(taskName, provider, status, total, success, fail, message, startedAt.toString());
    }

    private List<Instrument> toInstruments(List<InstrumentSnapshot> snapshots, String instrumentType) {
        List<Instrument> instruments = new ArrayList<>(snapshots.size());
        for (InstrumentSnapshot snapshot : snapshots) {
            String exchange = exchangeOf(snapshot.symbol());
            if (exchange == null) {
                continue;
            }
            String code = snapshot.code();
            instruments.add(new Instrument(
                    code + "." + exchange,
                    instrumentType,
                    exchange,
                    code,
                    snapshot.name(),
                    BoardClassifier.classify(code, instrumentType),
                    "CNY",
                    "Asia/Shanghai",
                    null,
                    null,
                    Instrument.STATUS_LISTED));
        }
        return instruments;
    }

    /** 从新浪的 symbol 前缀推导交易所：{@code sh600000} → {@code SH}。 */
    private String exchangeOf(String symbol) {
        if (symbol == null || symbol.length() < 3) {
            return null;
        }
        String prefix = symbol.substring(0, 2).toUpperCase(Locale.ROOT);
        return switch (prefix) {
            case "SH", "SZ", "BJ" -> prefix;
            default -> null;
        };
    }

    private String summarize(List<String> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        if (errors.size() <= 10) {
            return String.join("; ", errors);
        }
        return String.join("; ", errors.subList(0, 10)) + " ...（共 " + errors.size() + " 条）";
    }

    private <T> List<List<T>> partition(List<T> source, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            partitions.add(source.subList(i, Math.min(i + size, source.size())));
        }
        return partitions;
    }
}
