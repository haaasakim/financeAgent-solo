package org.haaasaki.financeagent.data.sync;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 命令行同步入口。
 *
 * <p>用法（配合 {@code --spring.main.web-application-type=none} 可让应用执行完即退出）：
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.instruments --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.init=50 --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.init=0 --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.incremental --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.missing --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.audit --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.backfill=500 --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.daily --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.snapshot --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.indexes --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--factor.init --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--factor.update --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--valuation.init --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--valuation.update --spring.main.web-application-type=none"
 * </pre>
 *
 * <p>{@code --sync.init=N} 中 N 表示最多处理 N 只标的（小样本验证用），0 或省略值表示全量。
 * {@code --sync.audit} 只审计日K覆盖（不写库、不发外部请求）；
 * {@code --sync.backfill=N} 对审计出的历史缺口整段回填，N 为本轮上限（0 表示不限）；
 * {@code --sync.daily} 执行每日全流程（字典 → 日K → 因子 → 估值 → 快照），供定时任务与外部调度使用；
 * {@code --sync.snapshot} 同步全市场快照，{@code --sync.indexes} 同步配置中的指数。
 * {@code --factor.init} 全量回填因子，{@code --factor.update} 增量重算；
 * {@code --valuation.init} 全量回填历史估值，{@code --valuation.update} 增量更新；
 * {@code --sync.incremental} 成功后会自动衔接一次因子增量与估值增量。
 *
 * <p>顺序必须晚于 {@link org.haaasaki.financeagent.data.migration.DatabaseMigrator}，
 * 否则表还没建好。
 */
@Component
@Order(3)
public class SyncCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncCommandLineRunner.class);

    private final MarketDataSyncService syncService;
    private final FactorComputeService factorComputeService;
    private final ValuationSyncService valuationSyncService;
    private final DataCoverageService coverageService;
    private final DailySyncService dailySyncService;

    public SyncCommandLineRunner(MarketDataSyncService syncService,
                                 FactorComputeService factorComputeService,
                                 ValuationSyncService valuationSyncService,
                                 DataCoverageService coverageService,
                                 DailySyncService dailySyncService) {
        this.syncService = syncService;
        this.factorComputeService = factorComputeService;
        this.valuationSyncService = valuationSyncService;
        this.coverageService = coverageService;
        this.dailySyncService = dailySyncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("sync.instruments")) {
            log.info("命令行触发：同步标的字典");
            report(syncService.syncInstruments());
        }
        if (args.containsOption("sync.init")) {
            int limit = intOption(args, "sync.init", 0);
            log.info("命令行触发：全量初始化日K（limit={}，0 表示不限）", limit);
            report(syncService.initializeDailyBars(limit));
        }
        if (args.containsOption("sync.incremental")) {
            log.info("命令行触发：增量同步日K");
            report(syncService.syncDailyIncremental());
            // 因子依赖日K，增量同步后自动重算最近窗口，避免用户忘记
            log.info("自动衔接：增量同步后重算因子");
            report(factorComputeService.updateFactors());
            log.info("自动衔接：增量同步后更新估值");
            report(valuationSyncService.updateValuation());
        }
        if (args.containsOption("sync.daily")) {
            log.info("命令行触发：每日同步（字典 → 日K → 因子 → 估值 → 快照）");
            report(dailySyncService.run());
        }
        if (args.containsOption("sync.missing")) {
            log.info("命令行触发：补拉缺失标的的日K");
            report(syncService.syncMissingDailyBars());
        }
        if (args.containsOption("sync.audit")) {
            log.info("命令行触发：审计日K覆盖");
            coverageService.audit();
        }
        if (args.containsOption("sync.backfill")) {
            int limit = intOption(args, "sync.backfill", 0);
            log.info("命令行触发：回填历史日K缺口（单轮上限={}，0 表示不限）", limit);
            report(coverageService.backfill(limit));
        }
        if (args.containsOption("sync.snapshot")) {
            log.info("命令行触发：同步市场快照");
            report(syncService.syncMarketSnapshots());
        }
        if (args.containsOption("sync.indexes")) {
            log.info("命令行触发：同步指数");
            report(syncService.syncIndexes());
        }
        if (args.containsOption("factor.init")) {
            log.info("命令行触发：全量回填因子");
            report(factorComputeService.initializeFactors());
        }
        if (args.containsOption("factor.update")) {
            log.info("命令行触发：增量重算因子");
            report(factorComputeService.updateFactors());
        }
        if (args.containsOption("valuation.init")) {
            log.info("命令行触发：全量回填历史估值");
            report(valuationSyncService.initializeValuation());
        }
        if (args.containsOption("valuation.update")) {
            log.info("命令行触发：增量更新历史估值");
            report(valuationSyncService.updateValuation());
        }
    }

    private int intOption(ApplicationArguments args, String name, int defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.get(0).trim());
        } catch (NumberFormatException e) {
            log.warn("参数 {} 不是整数，按默认值 {} 处理", name, defaultValue);
            return defaultValue;
        }
    }

    private void report(SyncResult result) {
        log.info("同步结果：task={} status={} total={} success={} fail={} message={}",
                result.taskName(), result.status(), result.total(),
                result.success(), result.fail(), result.message());
    }
}
