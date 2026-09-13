package org.haaasaki.financeagent.data.sync;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.config.MarketProperties;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.MarketSnapshotMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.InstrumentSnapshot;
import org.haaasaki.financeagent.data.model.MarketSnapshot;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.provider.SnapshotProvider;
import org.haaasaki.financeagent.data.support.BoardClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 行情数据同步服务。
 *
 * <p>入口：标的字典同步、日K全量初始化、日K增量更新、市场快照同步、指数同步。
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
    private final SyncLogRecorder syncLogRecorder;
    private final MarketSnapshotMapper marketSnapshotMapper;
    private final QuoteProvider quoteProvider;
    private final SnapshotProvider snapshotProvider;
    private final MarketProperties marketProperties;
    private final TransactionTemplate transactionTemplate;
    private final int historyYears;
    private final int batchSize;
    private final int snapshotMinTotal;

    public MarketDataSyncService(
            InstrumentMapper instrumentMapper,
            DailyBarMapper dailyBarMapper,
            SyncLogRecorder syncLogRecorder,
            MarketSnapshotMapper marketSnapshotMapper,
            QuoteProvider quoteProvider,
            SnapshotProvider snapshotProvider,
            MarketProperties marketProperties,
            TransactionTemplate transactionTemplate,
            @Value("${finance.sync.history-years:10}") int historyYears,
            @Value("${finance.sync.batch-size:500}") int batchSize,
            @Value("${finance.sync.snapshot-min-total:5000}") int snapshotMinTotal) {
        this.instrumentMapper = instrumentMapper;
        this.dailyBarMapper = dailyBarMapper;
        this.syncLogRecorder = syncLogRecorder;
        this.marketSnapshotMapper = marketSnapshotMapper;
        this.quoteProvider = quoteProvider;
        this.snapshotProvider = snapshotProvider;
        this.marketProperties = marketProperties;
        this.transactionTemplate = transactionTemplate;
        this.historyYears = historyYears;
        this.batchSize = batchSize;
        this.snapshotMinTotal = snapshotMinTotal;
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
            syncLogRecorder.record(taskName, snapshotProvider.name(), "SUCCESS",
                    instruments.size(), written, 0, message, startedAt);
            log.info("标的字典同步完成：{}", message);
            return new SyncResult(taskName, "SUCCESS", instruments.size(), written, 0, message);
        } catch (RuntimeException e) {
            syncLogRecorder.record(taskName, snapshotProvider.name(), "FAILED", 0, 0, 0, e.getMessage(), startedAt);
            throw e;
        }
    }

    /**
     * 全量初始化日K历史。
     *
     * @param limit 最多处理的标的数量，&lt;= 0 表示不限制。用于小样本验证。
     */
    public SyncResult initializeDailyBars(int limit) {
        List<Instrument> targets = instrumentMapper.findAllListed();
        if (limit > 0 && targets.size() > limit) {
            targets = targets.subList(0, limit);
        }
        return syncDailyBars(targets, "INIT_DAILY_BARS");
    }

    /**
     * 只补拉当前没有任何日K的标的，用于全量初始化中断或批量失败后的断点续传。
     *
     * <p>为什么可以按「零行」判定失败：Provider 先取完全部日期分段再返回，
     * 任一分段失败都会让整只标的抛错且不写入，因此失败标的在 daily_bar 中没有任何行。
     */
    public SyncResult syncMissingDailyBars() {
        List<Instrument> targets = instrumentMapper.findWithoutDailyBars();
        if (targets.isEmpty()) {
            log.info("补拉缺失日K：没有缺失标的");
            return new SyncResult("SYNC_MISSING_BARS", "SUCCESS", 0, 0, 0, "无缺失标的");
        }
        return syncDailyBars(targets, "SYNC_MISSING_BARS");
    }

    private SyncResult syncDailyBars(List<Instrument> targets, String taskName) {
        LocalDateTime startedAt = LocalDateTime.now();
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
                log.info("日K同步进度[{}] {}/{}（成功 {}，失败 {}）",
                        taskName, processed, targets.size(), success, fail);
            }
        }

        String status = fail == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL");
        String message = summarize(errors);
        syncLogRecorder.record(taskName, quoteProvider.name(), status, targets.size(), success, fail, message, startedAt);
        log.info("日K同步完成[{}]：总计 {}，成功 {}，失败 {}", taskName, targets.size(), success, fail);
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
        syncLogRecorder.record(taskName, quoteProvider.name(), status, targets.size(), success, fail, message, startedAt);
        log.info("日K增量完成：{}", message);
        return new SyncResult(taskName, status, targets.size(), success, fail, message);
    }

    /**
     * 同步全市场最新快照（行情 + 估值）。
     *
     * <p>整批语义：先完成全量拉取、条数校验与映射，再在一个事务内分批 upsert；
     * 任一步失败即整体回滚，不留下部分写入。落库后再次核对行数，防止静默丢失。
     *
     * <p>校验不通过抛 {@link DataIntegrityException}（不可重试）：包含返回为空、
     * 总数低于下限、低于已有标的数的 90%、映射条数不一致、落库条数不一致。
     */
    public SyncResult syncMarketSnapshots() {
        LocalDateTime startedAt = LocalDateTime.now();
        String taskName = "SYNC_MARKET_SNAPSHOT";
        int fetchedCount = 0;
        try {
            List<InstrumentSnapshot> stocks = snapshotProvider.fetchStocks();
            List<InstrumentSnapshot> etfs = snapshotProvider.fetchEtfs();
            if (stocks.isEmpty() || etfs.isEmpty()) {
                throw new DataIntegrityException(
                        "快照返回为空（股票 " + stocks.size() + "，ETF " + etfs.size() + "），整批拒绝");
            }

            List<InstrumentSnapshot> fetched = new ArrayList<>(stocks.size() + etfs.size());
            fetched.addAll(stocks);
            fetched.addAll(etfs);
            fetchedCount = fetched.size();
            verifyFetchedSize(fetchedCount);

            String fetchedAt = LocalDateTime.now().toString();
            List<MarketSnapshot> rows = toSnapshots(fetched, fetchedAt);
            if (rows.size() != fetched.size()) {
                throw new DataIntegrityException(
                        "快照映射条数不一致：拉取 " + fetched.size() + "，有效 " + rows.size());
            }

            final String runAt = fetchedAt;
            transactionTemplate.executeWithoutResult(status -> {
                for (List<MarketSnapshot> batch : partition(rows, batchSize)) {
                    marketSnapshotMapper.upsertBatch(batch);
                }
                long stored = marketSnapshotMapper.countByFetchedAt(runAt);
                if (stored != rows.size()) {
                    throw new DataIntegrityException(
                            "快照落库条数校验失败：期望 " + rows.size() + "，实际 " + stored);
                }
            });

            String message = "股票 " + stocks.size() + " + ETF " + etfs.size() + " = " + rows.size() + " 条";
            syncLogRecorder.record(taskName, snapshotProvider.name(), "SUCCESS",
                    fetchedCount, rows.size(), 0, message, startedAt);
            log.info("市场快照同步完成：{}", message);
            return new SyncResult(taskName, "SUCCESS", fetchedCount, rows.size(), 0, message);
        } catch (RuntimeException e) {
            syncLogRecorder.record(taskName, snapshotProvider.name(), "FAILED",
                    fetchedCount, 0, fetchedCount, e.getMessage(), startedAt);
            log.warn("市场快照同步失败：{}", e.getMessage());
            throw e;
        }
    }

    /** 对拉取条数做下限与已有标的规模比对，防止接口静默返回部分数据。 */
    private void verifyFetchedSize(int fetchedCount) {
        if (fetchedCount < snapshotMinTotal) {
            throw new DataIntegrityException(
                    "快照条数 " + fetchedCount + " 低于下限 " + snapshotMinTotal + "，整批拒绝");
        }
        long known = instrumentMapper.count();
        if (known > 0 && fetchedCount < known * 0.9) {
            throw new DataIntegrityException(
                    "快照条数 " + fetchedCount + " 不足已有标的数 " + known + " 的 90%，整批拒绝");
        }
    }

    private List<MarketSnapshot> toSnapshots(List<InstrumentSnapshot> snapshots, String fetchedAt) {
        List<MarketSnapshot> rows = new ArrayList<>(snapshots.size());
        for (InstrumentSnapshot snapshot : snapshots) {
            String exchange = exchangeOf(snapshot.symbol());
            if (exchange == null || snapshot.code() == null || snapshot.code().isBlank()) {
                throw new DataIntegrityException(
                        "快照出现无法解析的标的：symbol=" + snapshot.symbol() + " code=" + snapshot.code());
            }
            rows.add(new MarketSnapshot(
                    snapshot.code() + "." + exchange,
                    snapshot.lastPrice(),
                    snapshot.pctChg(),
                    snapshot.volume(),
                    snapshot.amount(),
                    snapshot.turnoverRate(),
                    snapshot.peTtm(),
                    snapshot.pb(),
                    snapshot.totalMv(),
                    snapshot.circMv(),
                    snapshotProvider.name(),
                    fetchedAt));
        }
        return rows;
    }

    /**
     * 同步配置中的指数（档案 + 10 年日K）。
     *
     * <p>指数清单来自配置而非外部列表接口：它们数量固定、名称稳定，
     * 用配置声明可以避免「某个指数被源站改名」这种无意义的数据漂移。
     */
    public SyncResult syncIndexes() {
        LocalDateTime startedAt = LocalDateTime.now();
        String taskName = "SYNC_INDEXES";
        List<MarketProperties.IndexDef> indexes = marketProperties.indexes();
        if (indexes == null || indexes.isEmpty()) {
            throw new DataIntegrityException("未配置指数清单（finance.market.indexes），整批拒绝");
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(historyYears);
        int success = 0;
        int fail = 0;
        List<String> errors = new ArrayList<>();

        for (MarketProperties.IndexDef def : indexes) {
            try {
                String[] parts = splitInstrumentId(def.id());
                String code = parts[0];
                String exchange = parts[1];
                instrumentMapper.upsertBatch(List.of(new Instrument(
                        def.id(), Instrument.TYPE_INDEX, exchange, code, def.name(),
                        BoardClassifier.INDEX, "CNY", "Asia/Shanghai", null, null,
                        Instrument.STATUS_LISTED)));

                List<DailyBar> bars = quoteProvider.fetchDailyBars(def.id(), start, end);
                if (bars.isEmpty()) {
                    fail++;
                    errors.add(def.id() + ": 区间内无数据");
                    continue;
                }
                for (List<DailyBar> batch : partition(bars, batchSize)) {
                    dailyBarMapper.upsertBatch(batch);
                }
                instrumentMapper.updateListedAt(def.id(), bars.get(0).tradeDate());
                success++;
            } catch (DataIntegrityException | DataSyncException e) {
                fail++;
                errors.add(def.id() + ": " + e.getMessage());
                log.warn("指数同步失败 {} - {}", def.id(), e.getMessage());
            }
        }

        String status = fail == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL");
        String message = "成功 " + success + "，失败 " + fail
                + (errors.isEmpty() ? "" : "；" + summarize(errors));
        syncLogRecorder.record(taskName, quoteProvider.name(), status, indexes.size(), success, fail, message, startedAt);
        log.info("指数同步完成：{}", message);
        return new SyncResult(taskName, status, indexes.size(), success, fail, message);
    }

    /** {@code 600000.SH} → {@code [600000, SH]}，并校验交易所合法。 */
    private String[] splitInstrumentId(String instrumentId) {
        int dot = instrumentId == null ? -1 : instrumentId.lastIndexOf('.');
        if (dot <= 0 || dot == instrumentId.length() - 1) {
            throw new DataIntegrityException("非法 instrumentId（期望 {code}.{exchange}）: " + instrumentId);
        }
        String code = instrumentId.substring(0, dot);
        String exchange = instrumentId.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (!exchange.equals("SH") && !exchange.equals("SZ") && !exchange.equals("BJ")) {
            throw new DataIntegrityException("非法交易所: " + instrumentId);
        }
        return new String[] {code, exchange};
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
