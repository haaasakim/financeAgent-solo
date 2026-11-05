package org.haaasaki.financeagent.data.sync;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.DataStatistics;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.support.DataStatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 收盘后自动同步调度。
 *
 * <p>默认在交易日 16:10（Asia/Shanghai）执行一次 {@link DailySyncService#run()}。
 * 是否交易日用基准指数（000300.SH）的当日日K探测：休市日没有当日 bar 就跳过，
 * 避免节假日空跑；若数据源延迟导致探测不到当日 bar，本次跳过，下一次增量会补上。
 *
 * <p>并发保护：同一个 JVM 内用 {@link AtomicBoolean} 防止定时任务与手动触发重叠；
 * 跨进程（例如手工再启动一次同步）依靠 upsert 幂等与 SQLite 写锁，不做额外协调。
 *
 * <p>应用在收盘后启动且当天数据尚未同步时，会在启动完成后异步补一次，避免因为
 * 错过定时点而等到第二天。是否执行由 {@code finance.sync.schedule.catch-up} 控制。
 *
 * <p>macOS 睡眠时单调时钟不前进，单靠 16:10 的 cron 会漏跑（实测日志只有 Hikari
 * clock leap，没有调度日志）。因此额外每 60 秒运行一次“看门狗”：用墙钟判断已过收盘点、
 * 当天是交易日且最新截面未更新时补跑一次。睡眠唤醒后最迟一个周期内即可补上。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "finance.sync.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class DailySyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySyncScheduler.class);

    private static final String BENCHMARK = "000300.SH";
    private static final LocalTime CATCH_UP_AFTER = LocalTime.of(16, 10);
    /** 交易日探测缓存时长：收盘前后数据可能延迟，避免每分钟打一次指数接口。 */
    private static final long PROBE_CACHE_MINUTES = 10;

    private final DailySyncService dailySyncService;
    private final QuoteProvider quoteProvider;
    private final DataStatisticsService statisticsService;
    private final String zone;
    private final boolean catchUpEnabled;
    private final boolean watchdogEnabled;
    private final long watchdogRetryMinutes;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile LocalDate probeDate;
    private volatile LocalDateTime probeAt;
    private volatile boolean probeResult;
    private volatile LocalDateTime lastWatchdogAttemptAt;

    public DailySyncScheduler(DailySyncService dailySyncService,
                              QuoteProvider quoteProvider,
                              DataStatisticsService statisticsService,
                              @Value("${finance.sync.schedule.zone:Asia/Shanghai}") String zone,
                              @Value("${finance.sync.schedule.catch-up:true}") boolean catchUpEnabled,
                              @Value("${finance.sync.schedule.cron:0 10 16 * * MON-FRI}") String cron,
                              @Value("${finance.sync.schedule.watchdog-enabled:true}") boolean watchdogEnabled,
                              @Value("${finance.sync.schedule.watchdog-retry-minutes:15}")
                              long watchdogRetryMinutes) {
        this.dailySyncService = dailySyncService;
        this.quoteProvider = quoteProvider;
        this.statisticsService = statisticsService;
        this.zone = zone;
        this.catchUpEnabled = catchUpEnabled;
        this.watchdogEnabled = watchdogEnabled;
        this.watchdogRetryMinutes = Math.max(1, watchdogRetryMinutes);
        log.info("收盘自动同步已启用：cron={}，zone={}，catch-up={}，watchdog={}，重试间隔={}分钟",
                cron, zone, catchUpEnabled, watchdogEnabled, this.watchdogRetryMinutes);
    }

    @Scheduled(cron = "${finance.sync.schedule.cron:0 10 16 * * MON-FRI}",
            zone = "${finance.sync.schedule.zone:Asia/Shanghai}")
    public void runScheduled() {
        runIfTradingDay(LocalDate.now(ZoneId.of(zone)), "定时任务");
    }

    /**
     * 墙钟看门狗：解决 macOS 睡眠导致 cron 漏跑。
     *
     * <p>每 60 秒检查一次（休眠期间不触发，唤醒后一个周期内恢复）。只有同时满足
     * 「已过 16:10」「当天是交易日」「最新截面未达到 90% 或最新日期不是今天」才补跑；
     * 失败后按 {@code watchdog-retry-minutes} 节流，避免数据源故障时高频重试。
     */
    @Scheduled(fixedDelayString = "${finance.sync.schedule.watchdog-ms:60000}",
            initialDelayString = "${finance.sync.schedule.watchdog-ms:60000}")
    public void watchdog() {
        if (!watchdogEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of(zone));
        try {
            if (shouldSync(now)) {
                run("看门狗");
            }
        } catch (RuntimeException e) {
            log.error("看门狗检查失败", e);
        }
    }

    /** 是否满足看门狗补同步条件；独立出来便于用固定时间测试。 */
    boolean shouldSync(LocalDateTime now) {
        if (now.toLocalTime().isBefore(CATCH_UP_AFTER)) {
            return false;
        }
        LocalDate today = now.toLocalDate();
        if (!isTradingDayCached(today, now)) {
            return false;
        }
        DataStatistics stats = statisticsService.collect();
        boolean current = today.equals(stats.maxTradeDate())
                && stats.instrumentTotal() > 0
                && stats.latestDateCovered() * 100 >= stats.instrumentTotal() * 90;
        if (current) {
            return false;
        }
        LocalDateTime last = lastWatchdogAttemptAt;
        if (last != null && Duration.between(last, now).toMinutes() < watchdogRetryMinutes) {
            return false;
        }
        lastWatchdogAttemptAt = now;
        log.info("看门狗：{} 已收盘，但最新交易日 {}（最新截面 {} 只），触发补同步",
                today, stats.maxTradeDate(), stats.latestDateCovered());
        return true;
    }

    /** 当天交易日探测加 10 分钟缓存；探测失败不缓存，下一周期重试。 */
    boolean isTradingDayCached(LocalDate date, LocalDateTime now) {
        LocalDateTime checkedAt = probeAt;
        if (date.equals(probeDate) && checkedAt != null
                && Duration.between(checkedAt, now).toMinutes() < PROBE_CACHE_MINUTES) {
            return probeResult;
        }
        boolean result = isTradingDay(date);
        probeDate = date;
        probeAt = now;
        probeResult = result;
        return result;
    }

    /**
     * 收盘后启动补同步：只在当天是交易日、已过收盘同步时点、且库内最新交易日落后于今天时触发。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!catchUpEnabled) {
            return;
        }
        ZoneId zoneId = ZoneId.of(zone);
        LocalTime now = LocalTime.now(zoneId);
        if (now.isBefore(CATCH_UP_AFTER)) {
            return;
        }
        LocalDate today = LocalDate.now(zoneId);
        LocalDate latest = statisticsService.collect().maxTradeDate();
        if (latest != null && !latest.isBefore(today)) {
            return;
        }
        log.info("启动补同步：库内最新交易日 {} 落后于今天 {}，异步执行一次每日同步", latest, today);
        Thread.ofVirtual().name("daily-sync-catch-up").start(
                () -> runIfTradingDay(today, "启动补同步"));
    }

    /** 交易日才执行；返回是否真正执行了同步。 */
    boolean runIfTradingDay(LocalDate date, String trigger) {
        if (!isTradingDay(date)) {
            log.info("自动同步（{}）：{} 不是交易日或当日行情尚未更新，跳过", trigger, date);
            return false;
        }
        return run(trigger);
    }

    /**
     * 执行一次每日同步。
     *
     * @return false 表示已有同步在运行或执行异常
     */
    public boolean run(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.warn("自动同步已在运行，忽略本次触发：{}", trigger);
            return false;
        }
        try {
            log.info("自动同步开始：{}（{}）", trigger, LocalDate.now(ZoneId.of(zone)));
            SyncResult result = dailySyncService.run();
            log.info("自动同步结束：{} status={} success={} fail={} message={}",
                    trigger, result.status(), result.success(), result.fail(), result.message());
            return result.isSuccess();
        } catch (RuntimeException e) {
            log.error("自动同步异常：{}", trigger, e);
            return false;
        } finally {
            running.set(false);
        }
    }

    boolean isTradingDay(LocalDate date) {
        try {
            List<DailyBar> bars = quoteProvider.fetchDailyBars(
                    BENCHMARK, date.minusDays(10), date);
            return bars.stream().anyMatch(bar -> date.equals(bar.tradeDate()));
        } catch (RuntimeException e) {
            log.warn("交易日探测失败（{}），本次跳过：{}", date, e.getMessage());
            return false;
        }
    }
}
