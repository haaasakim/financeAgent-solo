package org.haaasaki.financeagent.data.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 日K覆盖审计与缺口回填测试。
 *
 * <p>重点覆盖「有部分日K但历史不足」这一情形：它不会被
 * {@link MarketDataSyncService#syncMissingDailyBars()} 识别，只能由覆盖率审计发现。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:coveragetest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "finance.sync.coverage-start-grace-days=1",
        "finance.sync.coverage-stale-days=1"
})
class DataCoverageServiceTest {

    private static final LocalDate DAY1 = LocalDate.of(2024, 1, 2);
    private static final LocalDate DAY2 = LocalDate.of(2024, 1, 3);
    private static final LocalDate DAY3 = LocalDate.of(2024, 1, 4);
    private static final LocalDate DAY4 = LocalDate.of(2024, 1, 5);
    private static final LocalDate DAY5 = LocalDate.of(2024, 1, 8);
    private static final List<LocalDate> MARKET_DATES = List.of(DAY1, DAY2, DAY3, DAY4, DAY5);

    @Autowired
    private DataCoverageService coverageService;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private QuoteProvider quoteProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM instrument");
        jdbcTemplate.update("DELETE FROM sync_log");

        instrumentMapper.upsertBatch(List.of(
                instrument("000300.SH", Instrument.TYPE_INDEX, "SH", "沪深300", DAY1),
                instrument("000001.SZ", Instrument.TYPE_STOCK, "SZ", "平安银行", DAY1),
                instrument("600000.SH", Instrument.TYPE_STOCK, "SH", "浦发银行", DAY1),
                instrument("600002.SH", Instrument.TYPE_STOCK, "SH", "长期停牌", DAY1),
                instrument("600519.SH", Instrument.TYPE_STOCK, "SH", "贵州茅台", DAY5),
                instrument("600001.SH", Instrument.TYPE_STOCK, "SH", "沪市无数据", null)));
        // 全库交易日由基准指数提供；其余标的只写部分或零根
        for (LocalDate date : MARKET_DATES) {
            insertBar("000300.SH", date);
        }
        for (LocalDate date : MARKET_DATES) {
            insertBar("000001.SZ", date);
        }
        // 首根缺失（历史前置缺口）
        insertBar("600000.SH", DAY4);
        insertBar("600000.SH", DAY5);
        // 首根正常但末根过旧（停牌，不回填）
        insertBar("600002.SH", DAY1);
        insertBar("600002.SH", DAY2);
        insertBar("600519.SH", DAY5);
    }

    @Test
    @DisplayName("覆盖审计：前置缺口与零行判缺口，停牌判近期无成交，新上市与完整标的不算")
    void auditsCoverageGaps() {
        CoverageReport report = coverageService.audit();

        assertThat(report.latestTradeDate()).isEqualTo(DAY5);
        assertThat(report.totalInstruments()).isEqualTo(6);
        assertThat(report.gaps())
                .extracting(coverage -> coverage.instrument().instrumentId())
                .containsExactly("600000.SH", "600001.SH");
        assertThat(report.gaps())
                .allSatisfy(coverage -> assertThat(coverage.expectedBars()).isEqualTo(5));
        assertThat(report.gaps())
                .filteredOn(coverage -> coverage.instrument().instrumentId().equals("600000.SH"))
                .singleElement()
                .satisfies(coverage -> assertThat(coverage.actualBars()).isEqualTo(2));
        assertThat(report.stale())
                .extracting(coverage -> coverage.instrument().instrumentId())
                .containsExactly("600002.SH");
        assertThat(report.sufficientInstruments()).isEqualTo(3);
    }

    @Test
    @DisplayName("缺口回填：只对缺口标的整段重拉，停牌/完整/新上市标的都不拉取")
    void backfillsOnlyGaps() {
        given(quoteProvider.fetchDailyBars(anyString(), any(), any()))
                .willAnswer(invocation -> barsFor(invocation.getArgument(0)));

        SyncResult result = coverageService.backfill(0);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.success()).isEqualTo(2);
        assertThat(dailyBarMapper.countByInstrument("600000.SH")).isEqualTo(5L);
        assertThat(dailyBarMapper.countByInstrument("600001.SH")).isEqualTo(5L);
        CoverageReport after = coverageService.audit();
        assertThat(after.gaps()).isEmpty();
        assertThat(after.stale())
                .extracting(coverage -> coverage.instrument().instrumentId())
                .containsExactly("600002.SH");

        // 停牌、完整、新上市标的不能被重复拉取
        verify(quoteProvider, never()).fetchDailyBars(org.mockito.ArgumentMatchers.eq("600002.SH"),
                any(), any());
        verify(quoteProvider, never()).fetchDailyBars(org.mockito.ArgumentMatchers.eq("000001.SZ"),
                any(), any());
        verify(quoteProvider, never()).fetchDailyBars(org.mockito.ArgumentMatchers.eq("600519.SH"),
                any(), any());
    }

    @Test
    @DisplayName("缺口回填：limit 限制单轮数量，剩余缺口留待下一轮")
    void respectsBackfillLimit() {
        given(quoteProvider.fetchDailyBars(anyString(), any(), any()))
                .willAnswer(invocation -> barsFor(invocation.getArgument(0)));

        SyncResult result = coverageService.backfill(1);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.success()).isEqualTo(1);
        assertThat(dailyBarMapper.countByInstrument("600000.SH")).isEqualTo(5L);
        assertThat(dailyBarMapper.countByInstrument("600001.SH")).isZero();
        assertThat(coverageService.audit().gaps()).hasSize(1);
    }

    private List<DailyBar> barsFor(String instrumentId) {
        List<DailyBar> bars = new ArrayList<>();
        for (LocalDate date : MARKET_DATES) {
            bars.add(new DailyBar(instrumentId, date, 10.0, 10.1, 9.9, 10.0,
                    null, 1000L, null, null, null, DailyBar.ADJ_QFQ, "TEST"));
        }
        return bars;
    }

    private void insertBar(String instrumentId, LocalDate date) {
        dailyBarMapper.upsertBatch(List.of(new DailyBar(
                instrumentId, date, 10.0, 10.1, 9.9, 10.0,
                null, 1000L, null, null, null, DailyBar.ADJ_QFQ, "TEST")));
    }

    private Instrument instrument(String id, String type, String exchange, String name,
                                  LocalDate listedAt) {
        String code = id.substring(0, id.indexOf('.'));
        return new Instrument(id, type, exchange, code, name,
                type.equals(Instrument.TYPE_INDEX) ? "INDEX" : "MAIN",
                "CNY", "Asia/Shanghai", listedAt, null, Instrument.STATUS_LISTED);
    }
}
