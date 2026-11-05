package org.haaasaki.financeagent.data.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.DataStatistics;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.support.DataStatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 自动同步调度测试：交易日探测、休市跳过、看门狗补同步与执行汇总。 */
class DailySyncSchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private final DailySyncService dailySyncService = mock(DailySyncService.class);
    private final QuoteProvider quoteProvider = mock(QuoteProvider.class);
    private final DataStatisticsService statisticsService = mock(DataStatisticsService.class);
    private final DailySyncScheduler scheduler = new DailySyncScheduler(
            dailySyncService, quoteProvider, statisticsService, "Asia/Shanghai", false,
            "0 10 16 * * MON-FRI", true, 15);

    @BeforeEach
    void setUp() {
        given(dailySyncService.run())
                .willReturn(new SyncResult("DAILY_SYNC", "SUCCESS", 5, 5, 0, null));
    }

    @Test
    @DisplayName("基准当日有日K → 判定为交易日并执行同步")
    void runsOnTradingDay() {
        given(quoteProvider.fetchDailyBars(eq("000300.SH"), any(), any()))
                .willReturn(List.of(bar(TODAY.minusDays(1)), bar(TODAY)));

        boolean executed = scheduler.runIfTradingDay(TODAY, "test");

        assertThat(executed).isTrue();
        verify(dailySyncService).run();
    }

    @Test
    @DisplayName("基准当日无日K（休市或数据未更新）→ 跳过，不执行同步")
    void skipsOnNonTradingDay() {
        given(quoteProvider.fetchDailyBars(eq("000300.SH"), any(), any()))
                .willReturn(List.of(bar(TODAY.minusDays(1))));

        boolean executed = scheduler.runIfTradingDay(TODAY, "test");

        assertThat(executed).isFalse();
        verify(dailySyncService, never()).run();
    }

    @Test
    @DisplayName("交易日探测失败 → 失败关闭，跳过本次同步")
    void skipsWhenProbeFails() {
        given(quoteProvider.fetchDailyBars(eq("000300.SH"), any(), any()))
                .willThrow(new IllegalStateException("network down"));

        assertThat(scheduler.isTradingDay(TODAY)).isFalse();
        assertThat(scheduler.runIfTradingDay(TODAY, "test")).isFalse();
        verify(dailySyncService, never()).run();
    }

    @Test
    @DisplayName("同步执行异常 → run 返回 false，不向外抛")
    void returnsFalseWhenSyncThrows() {
        given(dailySyncService.run()).willThrow(new IllegalStateException("db locked"));

        assertThat(scheduler.run("test")).isFalse();
    }

    @Test
    @DisplayName("看门狗：收盘后最新截面未更新 → 触发补同步，15 分钟内不重复")
    void watchdogTriggersWhenAfterCloseAndStale() {
        given(quoteProvider.fetchDailyBars(eq("000300.SH"), any(), any()))
                .willReturn(List.of(bar(TODAY)));
        given(statisticsService.collect())
                .willReturn(stats(TODAY.minusDays(1), 7_292, 7_303));

        LocalDateTime afterClose = TODAY.atTime(16, 20);
        assertThat(scheduler.shouldSync(afterClose)).isTrue();
        assertThat(scheduler.shouldSync(afterClose.plusMinutes(5))).isFalse();
    }

    @Test
    @DisplayName("看门狗：收盘前不触发")
    void watchdogSkipsBeforeClose() {
        assertThat(scheduler.shouldSync(TODAY.atTime(16, 5))).isFalse();
        verify(dailySyncService, never()).run();
    }

    @Test
    @DisplayName("看门狗：最新交易日已是今天且截面完整 → 不触发")
    void watchdogSkipsWhenCurrent() {
        given(quoteProvider.fetchDailyBars(eq("000300.SH"), any(), any()))
                .willReturn(List.of(bar(TODAY)));
        given(statisticsService.collect())
                .willReturn(stats(TODAY, 7_292, 7_303));

        assertThat(scheduler.shouldSync(TODAY.atTime(21, 0))).isFalse();
    }

    @Test
    @DisplayName("看门狗：休市日不触发")
    void watchdogSkipsHoliday() {
        given(quoteProvider.fetchDailyBars(eq("000300.SH"), any(), any()))
                .willReturn(List.of(bar(TODAY.minusDays(1))));
        given(statisticsService.collect())
                .willReturn(stats(TODAY.minusDays(1), 7_292, 7_303));

        assertThat(scheduler.shouldSync(TODAY.atTime(21, 0))).isFalse();
    }

    private DataStatistics stats(LocalDate maxTradeDate, long latestCovered, long total) {
        return new DataStatistics(total, total - 1, 1, 11_866_199, total,
                latestCovered, LocalDate.of(2016, 9, 12), maxTradeDate,
                "DAILY_SYNC", "SUCCESS", maxTradeDate + "T16:30");
    }

    private DailyBar bar(LocalDate date) {
        return new DailyBar("000300.SH", date, 4000, 4010, 3990, 4005,
                null, 1000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
