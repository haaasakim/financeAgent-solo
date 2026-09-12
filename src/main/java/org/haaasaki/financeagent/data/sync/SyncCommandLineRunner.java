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
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.snapshot --spring.main.web-application-type=none"
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync.indexes --spring.main.web-application-type=none"
 * </pre>
 *
 * <p>{@code --sync.init=N} 中 N 表示最多处理 N 只标的（小样本验证用），0 或省略值表示全量。
 * {@code --sync.snapshot} 同步全市场快照，{@code --sync.indexes} 同步配置中的指数。
 *
 * <p>顺序必须晚于 {@link org.haaasaki.financeagent.data.migration.DatabaseMigrator}，
 * 否则表还没建好。
 */
@Component
@Order(3)
public class SyncCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncCommandLineRunner.class);

    private final MarketDataSyncService syncService;

    public SyncCommandLineRunner(MarketDataSyncService syncService) {
        this.syncService = syncService;
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
        }
        if (args.containsOption("sync.snapshot")) {
            log.info("命令行触发：同步市场快照");
            report(syncService.syncMarketSnapshots());
        }
        if (args.containsOption("sync.indexes")) {
            log.info("命令行触发：同步指数");
            report(syncService.syncIndexes());
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
