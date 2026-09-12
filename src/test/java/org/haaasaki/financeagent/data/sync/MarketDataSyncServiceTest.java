package org.haaasaki.financeagent.data.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.MarketSnapshotMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.InstrumentSnapshot;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.provider.SnapshotProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 除权检测测试。
 *
 * <p>这是同步逻辑中最容易出错的部分：前复权价在除权后会整体重算，
 * 若只做增量追加，历史数据会停留在旧基准上。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:synctest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        // 小样本测试绕过 5,000 条下限，单独用 3 条下限验证校验逻辑
        "finance.sync.snapshot-min-total=3"
})
class MarketDataSyncServiceTest {

    private static final String ID = "600000.SH";

    @Autowired
    private MarketDataSyncService syncService;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private MarketSnapshotMapper marketSnapshotMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private QuoteProvider quoteProvider;
    @MockitoBean
    private SnapshotProvider snapshotProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM market_snapshot");
        jdbcTemplate.update("DELETE FROM instrument");
        jdbcTemplate.update("DELETE FROM sync_log");
        instrumentMapper.upsertBatch(List.of(new Instrument(
                ID, Instrument.TYPE_STOCK, "SH", "600000", "浦发银行",
                "MAIN", "CNY", "Asia/Shanghai", null, null, Instrument.STATUS_LISTED)));
    }

    @Test
    @DisplayName("重叠日期收盘价不一致 → 判定除权 → 全量重拉并覆盖历史")
    void detectsAdjustmentAndReloadsAll() {
        // 库中已存旧复权基准的数据
        dailyBarMapper.upsertBatch(List.of(
                bar(LocalDate.of(2024, 9, 2), 10.00),
                bar(LocalDate.of(2024, 9, 3), 10.10)));

        // 增量窗口返回：9/3 的收盘价被重算为 9.09（发生除权）
        List<DailyBar> incremental = List.of(
                bar(LocalDate.of(2024, 9, 3), 9.09),
                bar(LocalDate.of(2024, 9, 4), 9.15));
        // 全量重拉返回：整段按新基准重算
        List<DailyBar> full = List.of(
                bar(LocalDate.of(2024, 9, 2), 9.00),
                bar(LocalDate.of(2024, 9, 3), 9.09),
                bar(LocalDate.of(2024, 9, 4), 9.15));
        given(quoteProvider.fetchDailyBars(eq(ID), any(), any()))
                .willReturn(incremental)
                .willReturn(full);

        SyncResult result = syncService.syncDailyIncremental();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.success()).isEqualTo(1);

        Map<LocalDate, Double> closes = dailyBarMapper.findCloseMap(
                ID, LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));
        // 关键断言：9/2 的旧值 10.00 必须被覆盖为 9.00，否则历史与新数据处于不同复权基准
        assertThat(closes)
                .containsEntry(LocalDate.of(2024, 9, 2), 9.00)
                .containsEntry(LocalDate.of(2024, 9, 3), 9.09)
                .containsEntry(LocalDate.of(2024, 9, 4), 9.15);

        // 两次调用：一次增量探测，一次全量重拉
        verify(quoteProvider, times(2)).fetchDailyBars(eq(ID), any(), any());
    }

    @Test
    @DisplayName("重叠日期一致 → 只做增量，不触发全量重拉")
    void skipsReloadWhenConsistent() {
        dailyBarMapper.upsertBatch(List.of(bar(LocalDate.of(2024, 9, 2), 7.56)));
        List<DailyBar> incremental = List.of(
                bar(LocalDate.of(2024, 9, 2), 7.56),
                bar(LocalDate.of(2024, 9, 3), 7.42));
        given(quoteProvider.fetchDailyBars(eq(ID), any(), any())).willReturn(incremental);

        syncService.syncDailyIncremental();

        verify(quoteProvider, times(1)).fetchDailyBars(eq(ID), any(), any());
        assertThat(dailyBarMapper.countByInstrument(ID)).isEqualTo(2L);
    }

    @Test
    @DisplayName("重复执行同一增量同步，行数不变（幂等）")
    void incrementalIsIdempotent() {
        List<DailyBar> incremental = List.of(
                bar(LocalDate.of(2024, 9, 2), 7.56),
                bar(LocalDate.of(2024, 9, 3), 7.42));
        given(quoteProvider.fetchDailyBars(eq(ID), any(), any())).willReturn(incremental);

        syncService.syncDailyIncremental();
        syncService.syncDailyIncremental();

        assertThat(dailyBarMapper.countByInstrument(ID)).isEqualTo(2L);
    }

    @Test
    @DisplayName("快照同步：整批落库，重复执行幂等")
    void snapshotSyncIsIdempotent() {
        given(snapshotProvider.name()).willReturn("SINA_MARKET_CENTER");
        given(snapshotProvider.fetchStocks()).willReturn(List.of(
                snapshot("sh600000", "600000", "浦发银行", 9.26, 0.012),
                snapshot("sh600519", "600519", "贵州茅台", 1685.0, 0.008)));
        given(snapshotProvider.fetchEtfs()).willReturn(List.of(
                snapshot("sh510300", "510300", "沪深300ETF", 3.98, 0.0075)));

        SyncResult first = syncService.syncMarketSnapshots();
        SyncResult second = syncService.syncMarketSnapshots();

        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(first.success()).isEqualTo(3);
        assertThat(second.status()).isEqualTo("SUCCESS");
        assertThat(marketSnapshotMapper.count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("快照返回为空：整批拒绝，不写入且失败记录落 sync_log")
    void snapshotSyncRejectsEmptyResponse() {
        given(snapshotProvider.name()).willReturn("SINA_MARKET_CENTER");
        given(snapshotProvider.fetchStocks()).willReturn(List.of());
        given(snapshotProvider.fetchEtfs()).willReturn(List.of(
                snapshot("sh510300", "510300", "沪深300ETF", 3.98, 0.0075)));

        assertThatThrownBy(() -> syncService.syncMarketSnapshots())
                .isInstanceOf(DataIntegrityException.class);

        assertThat(marketSnapshotMapper.count()).isZero();
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sync_log WHERE task_name = 'SYNC_MARKET_SNAPSHOT' AND status = 'FAILED'",
                Integer.class);
        assertThat(failed).isEqualTo(1);
    }

    @Test
    @DisplayName("快照条数低于下限：整批拒绝")
    void snapshotSyncRejectsTooFewRows() {
        given(snapshotProvider.name()).willReturn("SINA_MARKET_CENTER");
        given(snapshotProvider.fetchStocks()).willReturn(List.of(
                snapshot("sh600000", "600000", "浦发银行", 9.26, 0.012)));
        given(snapshotProvider.fetchEtfs()).willReturn(List.of(
                snapshot("sh510300", "510300", "沪深300ETF", 3.98, 0.0075)));

        assertThatThrownBy(() -> syncService.syncMarketSnapshots())
                .isInstanceOf(DataIntegrityException.class);
        assertThat(marketSnapshotMapper.count()).isZero();
    }

    @Test
    @DisplayName("指数同步：按配置落档案与日K，重复执行幂等")
    void syncIndexesUpsertsInstrumentsAndBars() {
        given(quoteProvider.fetchDailyBars(anyString(), any(), any()))
                .willAnswer(invocation -> List.of(barFor(
                        invocation.getArgument(0), LocalDate.of(2024, 9, 2), 3400.0)));

        SyncResult result = syncService.syncIndexes();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.total()).isEqualTo(4);
        assertThat(instrumentMapper.findById("000001.SH")).isPresent()
                .get().extracting(Instrument::instrumentType).isEqualTo(Instrument.TYPE_INDEX);
        assertThat(dailyBarMapper.countByInstrument("000001.SH")).isEqualTo(1L);

        syncService.syncIndexes();
        assertThat(dailyBarMapper.countByInstrument("000001.SH")).isEqualTo(1L);
    }

    @Test
    @DisplayName("补拉缺失标的：只处理没有任何日K的标的，已有日K的跳过")
    void syncsOnlyMissingInstruments() {
        instrumentMapper.upsertBatch(List.of(new Instrument(
                "000001.SZ", Instrument.TYPE_STOCK, "SZ", "000001", "平安银行",
                "MAIN", "CNY", "Asia/Shanghai", null, null, Instrument.STATUS_LISTED)));
        dailyBarMapper.upsertBatch(List.of(barFor("000001.SZ", LocalDate.of(2024, 9, 2), 11.0)));
        given(quoteProvider.fetchDailyBars(eq(ID), any(), any()))
                .willReturn(List.of(barFor(ID, LocalDate.of(2024, 9, 2), 9.0)));

        SyncResult result = syncService.syncMissingDailyBars();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.success()).isEqualTo(1);
        assertThat(dailyBarMapper.countByInstrument(ID)).isEqualTo(1L);
        verify(quoteProvider, never()).fetchDailyBars(eq("000001.SZ"), any(), any());
    }

    @Test
    @DisplayName("补拉缺失标的：无缺失时直接返回，不发起请求")
    void missingSyncIsNoopWhenNothingMissing() {
        dailyBarMapper.upsertBatch(List.of(barFor(ID, LocalDate.of(2024, 9, 2), 9.0)));

        SyncResult result = syncService.syncMissingDailyBars();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.total()).isZero();
        verifyNoInteractions(quoteProvider);
    }

    private InstrumentSnapshot snapshot(String symbol, String code, String name,
                                        double price, double pctChg) {
        return new InstrumentSnapshot(symbol, code, name, price, pctChg, 1000L, 10000.0,
                10.0, 1.0, 1_000_000_000.0, 900_000_000.0, 0.01);
    }

    private DailyBar barFor(String instrumentId, LocalDate date, double close) {
        return new DailyBar(instrumentId, date, close, close, close, close,
                null, 100L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }

    private DailyBar bar(LocalDate date, double close) {
        return new DailyBar(ID, date, close, close * 1.01, close * 0.99, close,
                null, 1000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
