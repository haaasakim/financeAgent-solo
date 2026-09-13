package org.haaasaki.financeagent.data.sync;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 命令行入口编排测试：重点验证 `--sync.incremental` 成功后自动衔接因子与估值增量。
 */
class SyncCommandLineRunnerTest {

    private final MarketDataSyncService syncService = mock(MarketDataSyncService.class);
    private final FactorComputeService factorComputeService = mock(FactorComputeService.class);
    private final ValuationSyncService valuationSyncService = mock(ValuationSyncService.class);
    private final SyncCommandLineRunner runner =
            new SyncCommandLineRunner(syncService, factorComputeService, valuationSyncService);

    @Test
    @DisplayName("--sync.incremental 成功后自动触发 FACTOR_UPDATE 与 VALUATION_UPDATE")
    void incrementalTriggersFactorAndValuationUpdate() {
        given(syncService.syncDailyIncremental())
                .willReturn(new SyncResult("INCR_DAILY_BARS", "SUCCESS", 1, 1, 0, null));
        given(factorComputeService.updateFactors())
                .willReturn(new SyncResult("FACTOR_UPDATE", "SUCCESS", 1, 1, 0, null));
        given(valuationSyncService.updateValuation())
                .willReturn(new SyncResult("VALUATION_UPDATE", "SUCCESS", 1, 1, 0, null));

        runner.run(new DefaultApplicationArguments("--sync.incremental"));

        verify(syncService).syncDailyIncremental();
        verify(factorComputeService).updateFactors();
        verify(valuationSyncService).updateValuation();
    }

    @Test
    @DisplayName("--factor.init / --factor.update / --valuation.init / --valuation.update 独立触发")
    void factorAndValuationFlagsTriggerIndependently() {
        given(factorComputeService.initializeFactors())
                .willReturn(new SyncResult("FACTOR_INIT", "SUCCESS", 1, 1, 0, null));
        given(factorComputeService.updateFactors())
                .willReturn(new SyncResult("FACTOR_UPDATE", "SUCCESS", 1, 1, 0, null));
        given(valuationSyncService.initializeValuation())
                .willReturn(new SyncResult("VALUATION_INIT", "SUCCESS", 1, 1, 0, null));
        given(valuationSyncService.updateValuation())
                .willReturn(new SyncResult("VALUATION_UPDATE", "SUCCESS", 1, 1, 0, null));

        runner.run(new DefaultApplicationArguments("--factor.init"));
        runner.run(new DefaultApplicationArguments("--factor.update"));
        runner.run(new DefaultApplicationArguments("--valuation.init"));
        runner.run(new DefaultApplicationArguments("--valuation.update"));

        verify(factorComputeService).initializeFactors();
        verify(factorComputeService).updateFactors();
        verify(valuationSyncService).initializeValuation();
        verify(valuationSyncService).updateValuation();
        verify(syncService, never()).syncDailyIncremental();
    }

    @Test
    @DisplayName("无参数时不执行任何任务")
    void noFlagsDoesNothing() {
        runner.run(new DefaultApplicationArguments());

        verify(syncService, never()).syncDailyIncremental();
        verify(factorComputeService, never()).initializeFactors();
        verify(factorComputeService, never()).updateFactors();
        verify(valuationSyncService, never()).initializeValuation();
        verify(valuationSyncService, never()).updateValuation();
    }
}
