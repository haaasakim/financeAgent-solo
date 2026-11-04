package org.haaasaki.financeagent.data.sync;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 每日收盘后同步编排。
 *
 * <p>把「标的字典 → 日K增量 → 因子增量 → 估值增量 → 市场快照」串成一个幂等流程，
 * 供定时任务与 {@code --sync.daily} 命令行入口共用。任一外部步骤失败不会中断后续
 * 独立步骤：日K失败时跳过依赖它的因子/估值，但仍同步快照；最终汇总为一条
 * {@code DAILY_SYNC} 审计记录。
 *
 * <p>不包含耗时较长的历史回填（{@code --sync.backfill}）与全量因子回填
 * （{@code --factor.init}），这两类由人工/一次性任务触发。
 */
@Service
public class DailySyncService {

    private static final Logger log = LoggerFactory.getLogger(DailySyncService.class);

    private static final String PROVIDER = "LOCAL";

    private final MarketDataSyncService marketDataSyncService;
    private final FactorComputeService factorComputeService;
    private final ValuationSyncService valuationSyncService;
    private final SyncLogRecorder syncLogRecorder;
    /** 跨进程互斥：8080/8081 同时运行、定时任务与手动 --sync.daily 重叠时只允许一个写库。 */
    private final Path lockPath;

    public DailySyncService(MarketDataSyncService marketDataSyncService,
                            FactorComputeService factorComputeService,
                            ValuationSyncService valuationSyncService,
                            SyncLogRecorder syncLogRecorder,
                            @Value("${finance.sync.daily-lock-path:data/.daily-sync.lock}") String lockPath) {
        this.marketDataSyncService = marketDataSyncService;
        this.factorComputeService = factorComputeService;
        this.valuationSyncService = valuationSyncService;
        this.syncLogRecorder = syncLogRecorder;
        this.lockPath = Path.of(lockPath);
    }

    /**
     * 执行一次完整的每日同步。
     *
     * @return 汇总结果；{@code taskName=DAILY_SYNC}
     */
    public SyncResult run() {
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                log.warn("每日同步锁已被本进程占用，跳过本次执行");
                return skipped();
            }
            if (lock == null) {
                log.warn("另一个实例正在执行每日同步（{} 被占用），跳过本次执行", lockPath);
                return skipped();
            }
            try (lock) {
                return runLocked();
            }
        } catch (IOException e) {
            log.error("每日同步锁文件打开失败，跳过本次执行：{}", lockPath, e);
            return skipped();
        }
    }

    private SyncResult skipped() {
        return new SyncResult("DAILY_SYNC", "SKIPPED", 5, 0, 0, "已有一个每日同步在运行");
    }

    private SyncResult runLocked() {
        LocalDateTime startedAt = LocalDateTime.now();
        List<String> errors = new ArrayList<>();
        int total = 5;
        int success = 0;
        int partial = 0;
        int fail = 0;
        int skipped = 0;

        log.info("每日同步开始");
        SyncResult instruments = runStep("SYNC_INSTRUMENTS",
                marketDataSyncService::syncInstruments, errors);
        success += countSuccess(instruments);
        partial += countPartial(instruments);
        fail += countFail(instruments);

        SyncResult bars = runStep("INCR_DAILY_BARS",
                marketDataSyncService::syncDailyIncremental, errors);
        success += countSuccess(bars);
        partial += countPartial(bars);
        fail += countFail(bars);

        if ("FAILED".equals(bars.status())) {
            skipped += 2;
            log.warn("日K增量失败，跳过因子与估值增量；市场快照仍会执行");
        } else {
            SyncResult factors = runStep("FACTOR_UPDATE",
                    factorComputeService::updateFactors, errors);
            success += countSuccess(factors);
            partial += countPartial(factors);
            fail += countFail(factors);

            SyncResult valuation = runStep("VALUATION_UPDATE",
                    valuationSyncService::updateValuation, errors);
            success += countSuccess(valuation);
            partial += countPartial(valuation);
            fail += countFail(valuation);
        }

        SyncResult snapshot = runStep("SYNC_MARKET_SNAPSHOT",
                marketDataSyncService::syncMarketSnapshots, errors);
        success += countSuccess(snapshot);
        partial += countPartial(snapshot);
        fail += countFail(snapshot);

        String status;
        if (fail == 0 && partial == 0) {
            status = "SUCCESS";
        } else if (success == 0 && fail > 0) {
            status = "FAILED";
        } else {
            status = "PARTIAL";
        }
        String message = summarize(errors);
        if (skipped > 0) {
            message = (message == null ? "" : message + "；") + "跳过 " + skipped + " 个依赖步骤";
        }
        syncLogRecorder.record("DAILY_SYNC", PROVIDER, status, total, success, fail, message, startedAt);
        log.info("每日同步结束：status={}，成功步骤 {}，部分成功 {}，失败步骤 {}，跳过 {}；{}",
                status, success, partial, fail, skipped, message == null ? "无错误" : message);
        return new SyncResult("DAILY_SYNC", status, total, success, fail, message);
    }

    private SyncResult runStep(String name, Supplier<SyncResult> action, List<String> errors) {
        long started = System.currentTimeMillis();
        try {
            SyncResult result = action.get();
            log.info("每日同步步骤 {}：status={}，total={}，success={}，fail={}，耗时 {}ms",
                    name, result.status(), result.total(), result.success(), result.fail(),
                    System.currentTimeMillis() - started);
            if (!"SUCCESS".equals(result.status())) {
                errors.add(name + "=" + result.status());
            }
            return result;
        } catch (RuntimeException e) {
            errors.add(name + " 异常: " + e.getMessage());
            log.error("每日同步步骤 {} 异常", name, e);
            return new SyncResult(name, "FAILED", 0, 0, 0, e.getMessage());
        }
    }

    private int countSuccess(SyncResult result) {
        return "SUCCESS".equals(result.status()) ? 1 : 0;
    }

    private int countPartial(SyncResult result) {
        return "PARTIAL".equals(result.status()) ? 1 : 0;
    }

    private int countFail(SyncResult result) {
        return "FAILED".equals(result.status()) ? 1 : 0;
    }

    private String summarize(List<String> errors) {
        return errors.isEmpty() ? null : String.join("；", errors);
    }
}
