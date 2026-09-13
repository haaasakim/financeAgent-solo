package org.haaasaki.financeagent.data.sync;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.ValuationDailyMapper;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.ValuationDaily;
import org.haaasaki.financeagent.data.provider.ValuationPoint;
import org.haaasaki.financeagent.data.provider.ValuationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 历史估值同步：全量回填与增量更新（东财数据中心，WI-007）。
 *
 * <p>取数方式（实测东财支持多代码 `in` 过滤与日期过滤，但**大批量深分页会触发连接重置**，
 * 见 docs/DATA_SOURCES.md 第 7.1 节）：
 * <ul>
 *   <li>全量/补缺：逐代码调用 {@link ValuationProvider#fetchByCodes}（pageSize 5,000
 *       一次取全单只历史），条数校验失败整批拒绝</li>
 *   <li>增量：先补齐「无记录或落后超过 30 天」的标的，再按全市场最新估值日次日起
 *       用 {@link ValuationProvider#fetchByDate} 抓取（内部按 7 天窗口分片），幂等 upsert</li>
 * </ul>
 *
 * <p>只处理已有日K的股票（ETF 无估值）；重复执行不产生重复数据。
 */
@Service
public class ValuationSyncService {

    private static final Logger log = LoggerFactory.getLogger(ValuationSyncService.class);
    /** 逐代码取数：小请求稳定；不要改大（深分页会被服务端重置连接）。 */
    private static final int CODE_BATCH = 1;
    private static final int STORE_BATCH = 2_000;
    private static final int PROGRESS_INTERVAL = 500;
    private static final int LAGGARD_WINDOW_DAYS = 30;

    private final ValuationProvider valuationProvider;
    private final InstrumentMapper instrumentMapper;
    private final ValuationDailyMapper valuationDailyMapper;
    private final SyncLogRecorder syncLogRecorder;

    public ValuationSyncService(ValuationProvider valuationProvider,
                                InstrumentMapper instrumentMapper,
                                ValuationDailyMapper valuationDailyMapper,
                                SyncLogRecorder syncLogRecorder) {
        this.valuationProvider = valuationProvider;
        this.instrumentMapper = instrumentMapper;
        this.valuationDailyMapper = valuationDailyMapper;
        this.syncLogRecorder = syncLogRecorder;
    }

    /** 全量回填：有日K的股票逐批抓全部历史。 */
    public SyncResult initializeValuation() {
        LocalDateTime startedAt = LocalDateTime.now();
        List<Instrument> targets = stocksWithDailyBars();
        Counters counters = new Counters();
        counters.total = targets.size();

        log.info("估值同步开始[VALUATION_INIT]：目标 {} 只股票，逐只取数", targets.size());
        for (List<Instrument> batch : partition(targets, CODE_BATCH)) {
            fetchCodesInto(batch, counters);
            logProgress("VALUATION_INIT", counters, targets.size());
        }
        return finish("VALUATION_INIT", counters, startedAt);
    }

    /** 增量更新：先补缺失/落后标的，再按日期抓全市场最近增量。 */
    public SyncResult updateValuation() {
        LocalDateTime startedAt = LocalDateTime.now();
        List<Instrument> targets = stocksWithDailyBars();
        Counters counters = new Counters();
        counters.total = targets.size();

        LocalDate globalMax = valuationDailyMapper.findGlobalMaxTradeDate().orElse(null);
        Set<String> recent = globalMax == null
                ? Set.of()
                : new HashSet<>(valuationDailyMapper.findInstrumentIdsWithRowSince(
                        globalMax.minusDays(LAGGARD_WINDOW_DAYS)));
        List<Instrument> stale = targets.stream()
                .filter(instrument -> !recent.contains(instrument.instrumentId()))
                .toList();
        counters.stale = stale.size();

        log.info("估值增量[VALUATION_UPDATE]：目标 {} 只，其中缺失/落后 {} 只", targets.size(), stale.size());
        for (List<Instrument> batch : partition(stale, CODE_BATCH)) {
            fetchCodesInto(batch, counters);
            logProgress("VALUATION_UPDATE", counters, targets.size());
        }

        if (globalMax != null) {
            applyDateUpdate(targets, globalMax.plusDays(1), counters);
        }
        return finish("VALUATION_UPDATE", counters, startedAt);
    }

    /** 按日期抓全市场增量，仅保留目标股票；写入幂等。 */
    private void applyDateUpdate(List<Instrument> targets, LocalDate from, Counters counters) {
        List<ValuationPoint> points = valuationProvider.fetchByDate(from);
        if (points.isEmpty()) {
            return;
        }
        Map<String, String> codeToId = targets.stream().collect(Collectors.toMap(
                Instrument::code, Instrument::instrumentId, (a, b) -> a));
        List<ValuationDaily> rows = points.stream()
                .filter(point -> codeToId.containsKey(point.code()))
                .map(point -> new ValuationDaily(codeToId.get(point.code()), point.tradeDate(),
                        point.peTtm(), point.pbMrq(), valuationProvider.name()))
                .toList();
        if (!rows.isEmpty()) {
            store(rows);
            counters.rows += rows.size();
            counters.dateRows = rows.size();
            counters.dateInstruments = rows.stream()
                    .map(ValuationDaily::instrumentId)
                    .distinct()
                    .count();
        }
    }

    /** 抓取一批代码的全量历史并写库；失败记入该批全部标的。 */
    private void fetchCodesInto(List<Instrument> batch, Counters counters) {
        try {
            List<String> codes = batch.stream().map(Instrument::code).toList();
            List<ValuationPoint> points = valuationProvider.fetchByCodes(codes);
            Map<String, String> codeToId = batch.stream().collect(Collectors.toMap(
                    Instrument::code, Instrument::instrumentId, (a, b) -> a));
            List<ValuationDaily> rows = points.stream()
                    .filter(point -> codeToId.containsKey(point.code()))
                    .map(point -> new ValuationDaily(codeToId.get(point.code()), point.tradeDate(),
                            point.peTtm(), point.pbMrq(), valuationProvider.name()))
                    .toList();
            if (!rows.isEmpty()) {
                store(rows);
                counters.rows += rows.size();
            }
            Set<String> present = rows.stream()
                    .map(ValuationDaily::instrumentId)
                    .collect(Collectors.toSet());
            counters.success += present.size();
            counters.empty += batch.size() - present.size();
        } catch (RuntimeException e) {
            counters.fail += batch.size();
            counters.errors.add(batch.get(0).code() + "~" + batch.get(batch.size() - 1).code()
                    + ": " + e.getMessage());
            log.warn("估值批次失败 {}~{} - {}", batch.get(0).code(),
                    batch.get(batch.size() - 1).code(), e.getMessage());
        }
        counters.processed += batch.size();
    }

    private void logProgress(String taskName, Counters counters, int total) {
        if (counters.processed % PROGRESS_INTERVAL < CODE_BATCH) {
            log.info("估值同步进度[{}] {}/{}（成功 {}，无数据 {}，失败 {}）",
                    taskName, counters.processed, total,
                    counters.success, counters.empty, counters.fail);
        }
    }

    private List<Instrument> stocksWithDailyBars() {
        return instrumentMapper.findWithDailyBars().stream()
                .filter(Instrument::isStock)
                .toList();
    }

    private void store(List<ValuationDaily> rows) {
        for (int i = 0; i < rows.size(); i += STORE_BATCH) {
            valuationDailyMapper.upsertBatch(rows.subList(i, Math.min(rows.size(), i + STORE_BATCH)));
        }
    }

    private List<List<Instrument>> partition(List<Instrument> list, int size) {
        List<List<Instrument>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            batches.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return batches;
    }

    private SyncResult finish(String taskName, Counters counters, LocalDateTime startedAt) {
        String status = counters.fail == 0
                ? "SUCCESS"
                : (counters.success == 0 && counters.empty == 0 && counters.dateRows == 0
                        ? "FAILED" : "PARTIAL");
        StringBuilder message = new StringBuilder();
        if (counters.stale > 0) {
            message.append("补齐/落后 ").append(counters.stale).append(" 只；");
        }
        message.append("成功 ").append(counters.success)
                .append("，无数据 ").append(counters.empty)
                .append("，失败 ").append(counters.fail)
                .append("，写入 ").append(counters.rows).append(" 行");
        if (counters.dateRows > 0) {
            message.append("（其中按日增量 ").append(counters.dateRows)
                    .append(" 行 / ").append(counters.dateInstruments).append(" 只）");
        }
        if (!counters.errors.isEmpty()) {
            message.append("；").append(summarize(counters.errors));
        }

        syncLogRecorder.record(taskName, valuationProvider.name(), status,
                counters.total, counters.success, counters.fail, message.toString(), startedAt);
        log.info("估值同步完成[{}]：{}", taskName, message);
        return new SyncResult(taskName, status, counters.total, counters.success,
                counters.fail, message.toString());
    }

    private String summarize(List<String> errors) {
        return errors.size() <= 5
                ? String.join("；", errors)
                : String.join("；", errors.subList(0, 5)) + " 等 " + errors.size() + " 条";
    }

    /** 任务累计计数。 */
    private static final class Counters {
        private int total;
        private int processed;
        private int stale;
        private int success;
        private int empty;
        private int fail;
        private long rows;
        private long dateRows;
        private long dateInstruments;
        private final List<String> errors = new ArrayList<>();
    }
}
