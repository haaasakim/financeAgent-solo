package org.haaasaki.financeagent.data.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 每日同步编排测试：步骤顺序、依赖跳过与状态汇总。 */
class DailySyncServiceTest {

    private final MarketDataSyncService marketDataSyncService = mock(MarketDataSyncService.class);
    private final FactorComputeService factorComputeService = mock(FactorComputeService.class);
    private final ValuationSyncService valuationSyncService = mock(ValuationSyncService.class);
    private final SyncLogRecorder syncLogRecorder = mock(SyncLogRecorder.class);
    private final DailySyncService dailySyncService = new DailySyncService(
            marketDataSyncService, factorComputeService, valuationSyncService, syncLogRecorder,
            "target/daily-sync-test.lock");

    @BeforeEach
    void setUp() {
        given(marketDataSyncService.syncInstruments())
                .willReturn(result("SYNC_INSTRUMENTS", "SUCCESS"));
        given(marketDataSyncService.syncDailyIncremental())
                .willReturn(result("INCR_DAILY_BARS", "SUCCESS"));
        given(factorComputeService.updateFactors())
                .willReturn(result("FACTOR_UPDATE", "SUCCESS"));
        given(valuationSyncService.updateValuation())
                .willReturn(result("VALUATION_UPDATE", "SUCCESS"));
        given(marketDataSyncService.syncMarketSnapshots())
                .willReturn(result("SYNC_MARKET_SNAPSHOT", "SUCCESS"));
    }

    @Test
    @DisplayName("全部成功：按字典→日K→因子→估值→快照顺序执行并汇总 SUCCESS")
    void runsAllStepsInOrder() {
        SyncResult result = dailySyncService.run();

        assertThat(result.taskName()).isEqualTo("DAILY_SYNC");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.success()).isEqualTo(5);
        assertThat(result.fail()).isZero();
        InOrder order = inOrder(marketDataSyncService, factorComputeService, valuationSyncService);
        order.verify(marketDataSyncService).syncInstruments();
        order.verify(marketDataSyncService).syncDailyIncremental();
        order.verify(factorComputeService).updateFactors();
        order.verify(valuationSyncService).updateValuation();
        order.verify(marketDataSyncService).syncMarketSnapshots();
        verify(syncLogRecorder).record(org.mockito.ArgumentMatchers.eq("DAILY_SYNC"),
                org.mockito.ArgumentMatchers.eq("LOCAL"),
                org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.eq(5),
                org.mockito.ArgumentMatchers.eq(5),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("日K失败：跳过因子与估值，但仍执行快照，状态 PARTIAL")
    void skipsDependentStepsWhenBarsFail() {
        given(marketDataSyncService.syncDailyIncremental())
                .willReturn(result("INCR_DAILY_BARS", "FAILED"));

        SyncResult result = dailySyncService.run();

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.success()).isEqualTo(2); // 字典 + 快照
        assertThat(result.fail()).isEqualTo(1);
        verify(factorComputeService, never()).updateFactors();
        verify(valuationSyncService, never()).updateValuation();
        verify(marketDataSyncService).syncMarketSnapshots();
    }

    @Test
    @DisplayName("某一步抛异常：记录为失败并继续执行后续独立步骤")
    void continuesWhenStepThrows() {
        given(marketDataSyncService.syncInstruments())
                .willThrow(new IllegalStateException("sina down"));

        SyncResult result = dailySyncService.run();

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.fail()).isEqualTo(1);
        assertThat(result.message()).contains("SYNC_INSTRUMENTS 异常: sina down");
        verify(marketDataSyncService).syncDailyIncremental();
        verify(marketDataSyncService).syncMarketSnapshots();
    }

    @Test
    @DisplayName("全部步骤失败：汇总为 FAILED")
    void allStepsFailed() {
        given(marketDataSyncService.syncInstruments())
                .willReturn(result("SYNC_INSTRUMENTS", "FAILED"));
        given(marketDataSyncService.syncDailyIncremental())
                .willReturn(result("INCR_DAILY_BARS", "FAILED"));
        given(marketDataSyncService.syncMarketSnapshots())
                .willReturn(result("SYNC_MARKET_SNAPSHOT", "FAILED"));

        SyncResult result = dailySyncService.run();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.success()).isZero();
        assertThat(result.fail()).isEqualTo(3);
    }

    private SyncResult result(String taskName, String status) {
        return new SyncResult(taskName, status, 1, "SUCCESS".equals(status) ? 1 : 0,
                "FAILED".equals(status) ? 1 : 0, null);
    }
}
