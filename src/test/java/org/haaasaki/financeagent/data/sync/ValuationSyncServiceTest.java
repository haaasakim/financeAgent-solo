package org.haaasaki.financeagent.data.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.ValuationDailyMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.provider.ValuationPoint;
import org.haaasaki.financeagent.data.provider.ValuationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 估值同步服务测试：批量回填只含股票、幂等、失败整批拒绝、增量按日期取数并过滤非目标代码。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:valuationsynctest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class ValuationSyncServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 11);
    private static final List<String> CODE_A = List.of("000001");
    private static final List<String> CODE_B = List.of("600000");

    @Autowired
    private ValuationSyncService valuationSyncService;
    @Autowired
    private ValuationDailyMapper valuationDailyMapper;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ValuationProvider valuationProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM valuation_daily");
        jdbcTemplate.update("DELETE FROM sync_log");
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM instrument");

        instrumentMapper.upsertBatch(List.of(
                instrument("000001.SZ", "000001", Instrument.TYPE_STOCK),
                instrument("600000.SH", "600000", Instrument.TYPE_STOCK),
                instrument("510300.SH", "510300", Instrument.TYPE_ETF)));
        dailyBarMapper.upsertBatch(List.of(
                bar("000001.SZ"), bar("600000.SH"), bar("510300.SH")));

        when(valuationProvider.name()).thenReturn("TEST_VALUATION");
    }

    @Test
    @DisplayName("回填只请求有日K的股票；无数据计跳过；逐只写入幂等")
    void backfillsStocksOnlyAndIsIdempotent() {
        when(valuationProvider.fetchByCodes(CODE_A)).thenReturn(List.of(
                new ValuationPoint("000001", DATE, 5.0, 0.6),
                new ValuationPoint("000001", DATE.minusDays(1), 5.1, 0.61)));
        when(valuationProvider.fetchByCodes(CODE_B)).thenReturn(List.of());

        SyncResult first = valuationSyncService.initializeValuation();
        SyncResult second = valuationSyncService.initializeValuation();

        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.success()).isEqualTo(1);
        assertThat(first.message()).contains("无数据 1").contains("写入 2 行");
        assertThat(second.isSuccess()).isTrue();
        assertThat(valuationDailyMapper.count()).isEqualTo(2);
        assertThat(valuationDailyMapper.countDistinctInstruments()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sync_log WHERE task_name = 'VALUATION_INIT'", Long.class))
                .isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(valuationProvider, org.mockito.Mockito.atLeastOnce()).fetchByCodes(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(codes ->
                assertThat(codes).doesNotContain("510300"));
    }

    @Test
    @DisplayName("单标的失败：该标的记为失败，其他标的照常写入，不产生部分数据")
    void rejectsFailedInstrumentWithoutPartialWrite() {
        when(valuationProvider.fetchByCodes(CODE_A)).thenReturn(List.of(
                new ValuationPoint("000001", DATE, 5.0, 0.6)));
        when(valuationProvider.fetchByCodes(CODE_B))
                .thenThrow(new DataSyncException("条数校验失败"));

        SyncResult result = valuationSyncService.initializeValuation();

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.fail()).isEqualTo(1);
        assertThat(result.message()).contains("条数校验失败");
        assertThat(valuationDailyMapper.count()).isEqualTo(1);
        assertThat(valuationDailyMapper.countDistinctInstruments()).isEqualTo(1);
    }

    @Test
    @DisplayName("增量：缺失标的走全量，其余按日期抓取并过滤非目标代码")
    void updateFillsStaleCodesAndAppliesDateDelta() {
        when(valuationProvider.fetchByCodes(CODE_A)).thenReturn(List.of(
                new ValuationPoint("000001", DATE, 5.0, 0.6)));
        when(valuationProvider.fetchByCodes(CODE_B)).thenReturn(List.of());
        valuationSyncService.initializeValuation();

        when(valuationProvider.fetchByCodes(CODE_B)).thenReturn(List.of(
                new ValuationPoint("600000", DATE.minusDays(2), 12.0, 1.1)));
        when(valuationProvider.fetchByDate(DATE.plusDays(1))).thenReturn(List.of(
                new ValuationPoint("000001", DATE.plusDays(1), 5.2, 0.62),
                new ValuationPoint("999999", DATE.plusDays(1), 1.0, 0.1)));

        SyncResult result = valuationSyncService.updateValuation();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(valuationDailyMapper.count()).isEqualTo(3);
        assertThat(valuationDailyMapper.countDistinctInstruments()).isEqualTo(2);
        verify(valuationProvider).fetchByDate(DATE.plusDays(1));
    }

    private Instrument instrument(String id, String code, String type) {
        String[] parts = id.split("\\.");
        return new Instrument(id, type, parts[1], code, "标的" + code, "MAIN",
                "CNY", "Asia/Shanghai", LocalDate.of(2000, 1, 1), null, Instrument.STATUS_LISTED);
    }

    private DailyBar bar(String id) {
        return new DailyBar(id, DATE, 10, 10, 10, 10, 10.0,
                1000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
