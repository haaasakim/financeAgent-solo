package org.haaasaki.financeagent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

import org.haaasaki.financeagent.data.model.DataStatistics;
import org.haaasaki.financeagent.data.support.DataStatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时打印本地数据状态摘要。
 *
 * <p>执行顺序晚于 {@link org.haaasaki.financeagent.data.migration.DatabaseMigrator}（表要先建好）、
 * 早于 {@link org.haaasaki.financeagent.data.sync.SyncCommandLineRunner}（先看现状再看同步结果）。
 */
@Component
@Order(2)
public class StartupSummaryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSummaryRunner.class);

    private static final String HEAVY = "============================================================";
    private static final String THIN = "------------------------------------------------------------";
    private static final String SYNC_HINT =
            " 同步命令   : --sync.instruments | --sync.init=N（0 为全量）| --sync.missing（补缺失）\n"
                    + "              --sync.audit（覆盖审计）| --sync.backfill=N（历史缺口回填）\n"
                    + "              --sync.daily（收盘全流程）| --sync.incremental | --sync.snapshot\n"
                    + "              --factor.init（因子回填）| --factor.update（因子增量）\n"
                    + "              --valuation.init（估值回填）| --valuation.update（估值增量）";

    private final DataStatisticsService statisticsService;
    private final ApplicationContext applicationContext;
    private final String datasourceUrl;

    public StartupSummaryRunner(DataStatisticsService statisticsService,
                                ApplicationContext applicationContext,
                                @Value("${spring.datasource.url}") String datasourceUrl) {
        this.statisticsService = statisticsService;
        this.applicationContext = applicationContext;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info(buildSummary(statisticsService.collect()));
        printServerUrl();
    }

    private String buildSummary(DataStatistics stats) {
        String database = dbPath() + " (" + fileSize() + ")";

        if (stats.isEmpty()) {
            return "\n" + HEAVY + "\n"
                    + " financeAgent · A 股个人投资助手\n"
                    + THIN + "\n"
                    + " 数据库     : " + database + "\n"
                    + " 数据状态   : 空库，尚无任何标的与行情\n"
                    + THIN + "\n"
                    + SYNC_HINT + "\n"
                    + " 首次使用   : 先执行 --sync.instruments，再执行 --sync.init=0，"
                    + "然后 --sync.snapshot 与 --sync.indexes\n"
                    + HEAVY;
        }

        return "\n" + HEAVY + "\n"
                + " financeAgent · A 股个人投资助手\n"
                + THIN + "\n"
                + " 数据库     : " + database + "\n"
                + " 标的字典   : " + format(stats.instrumentTotal()) + " 只"
                + "（股票 " + format(stats.stockTotal()) + " · ETF " + format(stats.etfTotal()) + "）\n"
                + " 日K数据    : " + format(stats.dailyBarTotal()) + " 行"
                + " · 有数据 " + format(stats.coveredInstruments()) + " 只"
                + " · 最新交易日 " + format(stats.latestDateCovered()) + " 只\n"
                + " 日期范围   : " + range(stats) + "\n"
                + " 最近同步   : " + lastSync(stats) + "\n"
                + THIN + "\n"
                + SYNC_HINT + "\n"
                + HEAVY;
    }

    private String range(DataStatistics stats) {
        LocalDate min = stats.minTradeDate();
        LocalDate max = stats.maxTradeDate();
        if (min == null || max == null) {
            return "（无行情数据）";
        }
        return min + " ~ " + max;
    }

    private String lastSync(DataStatistics stats) {
        if (stats.lastSyncTask() == null) {
            return "（无同步记录）";
        }
        String finishedAt = stats.lastSyncFinishedAt();
        if (finishedAt != null && finishedAt.length() >= 19) {
            finishedAt = finishedAt.substring(0, 19).replace('T', ' ');
        }
        return stats.lastSyncTask() + " " + stats.lastSyncStatus() + " @ " + finishedAt;
    }

    private String format(long value) {
        return String.format("%,d", value);
    }

    /** {@code jdbc:sqlite:./data/finance.db} → {@code ./data/finance.db} */
    private String dbPath() {
        String prefix = "jdbc:sqlite:";
        return datasourceUrl.startsWith(prefix) ? datasourceUrl.substring(prefix.length()) : datasourceUrl;
    }

    private String fileSize() {
        Path path = Paths.get(dbPath());
        if (!Files.exists(path)) {
            return "尚未创建";
        }
        try {
            long bytes = Files.size(path);
            if (bytes < 1024 * 1024) {
                return String.format("%.0f KB", bytes / 1024.0);
            }
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        } catch (IOException e) {
            return "大小未知";
        }
    }

    /**
     * 打印可点击的服务地址。
     *
     * <p>使用 OSC 8 超链接（iTerm2、macOS Terminal、VS Code 终端、Windows Terminal 均支持）；
     * 不支持的终端会忽略转义序列，只显示纯文本，不会出现乱码。
     *
     * <p>走 {@code System.out} 而非日志：OSC 8 的转义字符不应写进日志文件。
     * 端口取自实际启动的 WebServer，因此端口被占用而自动切换时也能显示正确地址。
     *
     * <p>仅在 Web 模式下列印；{@code --spring.main.web-application-type=none} 时直接跳过。
     */
    private void printServerUrl() {
        if (!(applicationContext instanceof WebServerApplicationContext webContext)) {
            return;
        }
        WebServer webServer = webContext.getWebServer();
        if (webServer == null) {
            return;
        }
        String url = "http://localhost:" + webServer.getPort();
        System.out.println();
        System.out.println("  服务已启动  →  \u001B]8;;" + url + "\u001B\\" + url + "\u001B]8;;\u001B\\");
        System.out.println();
    }
}
