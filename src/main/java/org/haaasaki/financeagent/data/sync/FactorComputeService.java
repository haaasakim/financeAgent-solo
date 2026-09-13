package org.haaasaki.financeagent.data.sync;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.indicator.FactorCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 因子计算任务：把日K派生成 {@code factor_daily} 技术因子宽表。
 *
 * <p>纯本地计算，无外部请求。两个入口：
 * <ul>
 *   <li>{@link #initializeFactors()}：全历史回填，任务名 {@code FACTOR_INIT}</li>
 *   <li>{@link #updateFactors()}：增量重算，任务名 {@code FACTOR_UPDATE}；
 *       从未算过的标的自动转全量回填</li>
 * </ul>
 *
 * <p>为什么增量只重算最近 {@value #UPDATE_OUTPUT_BARS} 根 bar：均线 / 52 周高点存在最长
 * {@value #HIGH_WINDOW} 根的回看依赖，新增一天数据只会影响尾部窗口内的因子值；
 * 计算时取 {@value #UPDATE_SLICE_BARS} 根切片，仅 upsert 尾部输出窗口，
 * 避免切片头部因回看不足把历史正确值覆盖成 NULL。
 *
 * <p>幂等：upsert 主键 {@code (instrument_id, trade_date)}；重复执行行数不变。
 */
@Service
public class FactorComputeService {

    private static final Logger log = LoggerFactory.getLogger(FactorComputeService.class);

    private static final int PROGRESS_INTERVAL = 500;
    /** 最长回看：52 周高点窗口。 */
    private static final int HIGH_WINDOW = FactorCalculator.HIGH_WINDOW;
    /** 增量输出窗口：覆盖 MA60（60）与 52 周高点（250）的回看依赖，并留缓冲。 */
    static final int UPDATE_OUTPUT_BARS = 260;
    /** 增量计算切片：输出窗口 + 最长回看。 */
    static final int UPDATE_SLICE_BARS = UPDATE_OUTPUT_BARS + HIGH_WINDOW;
    private static final LocalDate EARLIEST = LocalDate.of(1990, 1, 1);
    private static final String PROVIDER = "LOCAL";

    private final InstrumentMapper instrumentMapper;
    private final DailyBarMapper dailyBarMapper;
    private final FactorDailyMapper factorDailyMapper;
    private final SyncLogRecorder syncLogRecorder;
    private final int batchSize;

    public FactorComputeService(InstrumentMapper instrumentMapper,
                                DailyBarMapper dailyBarMapper,
                                FactorDailyMapper factorDailyMapper,
                                SyncLogRecorder syncLogRecorder,
                                @Value("${finance.sync.batch-size:500}") int batchSize) {
        this.instrumentMapper = instrumentMapper;
        this.dailyBarMapper = dailyBarMapper;
        this.factorDailyMapper = factorDailyMapper;
        this.syncLogRecorder = syncLogRecorder;
        this.batchSize = batchSize;
    }

    /** 全历史回填：对已有日K的标的计算全部交易日的因子。 */
    public SyncResult initializeFactors() {
        LocalDateTime startedAt = LocalDateTime.now();
        String taskName = "FACTOR_INIT";
        List<Instrument> targets = instrumentMapper.findWithDailyBars();
        LocalDate today = LocalDate.now();

        int success = 0;
        int fail = 0;
        int processed = 0;
        List<String> errors = new ArrayList<>();

        for (Instrument instrument : targets) {
            processed++;
            try {
                List<DailyBar> bars =
                        dailyBarMapper.findRange(instrument.instrumentId(), EARLIEST, today);
                if (bars.isEmpty()) {
                    fail++;
                    errors.add(instrument.instrumentId() + ": 区间内无数据");
                } else {
                    store(FactorCalculator.calculate(bars));
                    success++;
                }
            } catch (RuntimeException e) {
                fail++;
                errors.add(instrument.instrumentId() + ": " + e.getMessage());
                log.warn("因子回填失败 {} - {}", instrument.instrumentId(), e.getMessage());
            }
            if (processed % PROGRESS_INTERVAL == 0) {
                log.info("因子回填进度 {}/{}（成功 {}，失败 {}）", processed, targets.size(), success, fail);
            }
        }

        return finish(taskName, targets.size(), success, fail, errors, startedAt);
    }

    /** 增量重算：已有因子的标的重算最近窗口，从未算过的全量回填。 */
    public SyncResult updateFactors() {
        LocalDateTime startedAt = LocalDateTime.now();
        String taskName = "FACTOR_UPDATE";
        List<Instrument> targets = instrumentMapper.findWithDailyBars();
        Set<String> existing = new HashSet<>(factorDailyMapper.findInstrumentIdsWithFactors());
        LocalDate today = LocalDate.now();

        int success = 0;
        int fail = 0;
        int processed = 0;
        List<String> errors = new ArrayList<>();

        for (Instrument instrument : targets) {
            processed++;
            try {
                List<FactorDaily> rows;
                if (existing.contains(instrument.instrumentId())) {
                    List<DailyBar> slice =
                            dailyBarMapper.findRecent(instrument.instrumentId(), UPDATE_SLICE_BARS);
                    rows = tail(FactorCalculator.calculate(slice), UPDATE_OUTPUT_BARS);
                } else {
                    List<DailyBar> all =
                            dailyBarMapper.findRange(instrument.instrumentId(), EARLIEST, today);
                    rows = FactorCalculator.calculate(all);
                }
                if (rows.isEmpty()) {
                    fail++;
                    errors.add(instrument.instrumentId() + ": 区间内无数据");
                } else {
                    store(rows);
                    success++;
                }
            } catch (RuntimeException e) {
                fail++;
                errors.add(instrument.instrumentId() + ": " + e.getMessage());
                log.warn("因子增量失败 {} - {}", instrument.instrumentId(), e.getMessage());
            }
            if (processed % PROGRESS_INTERVAL == 0) {
                log.info("因子增量进度 {}/{}（成功 {}，失败 {}）", processed, targets.size(), success, fail);
            }
        }

        return finish(taskName, targets.size(), success, fail, errors, startedAt);
    }

    /** 只保留尾部 {@code limit} 行；切片头部因回看不足算出的 NULL 不落库，防止覆盖历史正确值。 */
    private List<FactorDaily> tail(List<FactorDaily> rows, int limit) {
        return rows.size() <= limit ? rows : rows.subList(rows.size() - limit, rows.size());
    }

    private void store(List<FactorDaily> rows) {
        for (List<FactorDaily> batch : partition(rows, batchSize)) {
            factorDailyMapper.upsertBatch(batch);
        }
    }

    private SyncResult finish(String taskName, int total, int success, int fail,
                              List<String> errors, LocalDateTime startedAt) {
        String status = fail == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL");
        String message = summarize(errors);
        syncLogRecorder.record(taskName, PROVIDER, status, total, success, fail, message, startedAt);
        log.info("{} 完成：总计 {}，成功 {}，失败 {}", taskName, total, success, fail);
        return new SyncResult(taskName, status, total, success, fail, message);
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
