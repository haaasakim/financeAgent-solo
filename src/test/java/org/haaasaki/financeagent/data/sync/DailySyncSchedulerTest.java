package org.haaasaki.financeagent.data.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.support.DataStatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 自动同步调度测试：交易日探测、休市跳过与执行汇总。 */
class DailySyncSchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private final DailySyncService dailySyncService = mock(DailySyncService.class);
    private final QuoteProvider quoteProvider = mock(QuoteProvider.class);
    private final DataStatisticsService statisticsService = mock(DataStatisticsService.class);
    private final DailySyncScheduler scheduler = new DailySyncScheduler(
            dailySyncService, quoteProvider, statisticsService, "Asia/Shanghai", false,
            "0 10 16 * * MON-FRI");

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

    private DailyBar bar(LocalDate date) {
        return new DailyBar("000300.SH", date, 4000, 4010, 3990, 4005,
                null, 1000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
