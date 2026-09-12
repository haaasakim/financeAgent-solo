package org.haaasaki.financeagent.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.MarketSnapshotMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.MarketSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 市场查询测试：用真实 SQLite 临时库验证筛选、排序、分页与详情组装。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:markettest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class MarketQueryServiceTest {

    private static final String FETCHED_AT = "2026-09-12T15:30:00";

    @Autowired
    private MarketQueryService marketQueryService;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private MarketSnapshotMapper marketSnapshotMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM market_snapshot");
        jdbcTemplate.update("DELETE FROM instrument");

        instrumentMapper.upsertBatch(List.of(
                instrument("600000.SH", "STOCK", "MAIN", "浦发银行"),
                instrument("000001.SZ", "STOCK", "MAIN", "平安银行"),
                instrument("300750.SZ", "STOCK", "GEM", "宁德时代"),
                instrument("688981.SH", "STOCK", "STAR", "中芯国际"),
                instrument("510300.SH", "ETF", "ETF", "沪深300ETF"),
                instrument("000300.SH", "INDEX", "INDEX", "沪深300")));

        marketSnapshotMapper.upsertBatch(List.of(
                snapshot("600000.SH", 9.26, 0.012, 6.05e8, 4.8, 2.845e11),
                snapshot("000001.SZ", 11.35, -0.0053, 2.2e9, 5.2, 2.2e11),
                snapshot("300750.SZ", 198.40, 0.0235, 8.7e9, 22.1, 8.73e11),
                snapshot("510300.SH", 3.98, 0.0075, 1.2e9, null, 1.23e11)));

        LocalDate today = LocalDate.now();
        dailyBarMapper.upsertBatch(List.of(
                bar("600000.SH", today.minusDays(2), 9.10),
                bar("600000.SH", today.minusDays(1), 9.26),
                bar("000001.SH", today.minusDays(2), 3400.0),
                bar("000001.SH", today.minusDays(1), 3412.5),
                bar("000300.SH", today.minusDays(1), 4012.7)));
    }

    @Test
    @DisplayName("默认列表：只含股票与 ETF，指数不进列表，按代码升序")
    void listsStocksAndEtfsOnly() {
        MarketListResult result = marketQueryService.list(defaultQuery());

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.rows()).extracting(row -> row.code())
                .containsExactly("000001", "300750", "510300", "600000", "688981");
    }

    @Test
    @DisplayName("筛选：类型、板块、关键词可组合")
    void filtersByTypeBoardKeyword() {
        MarketListResult stocks = marketQueryService.list(
                MarketListQuery.of(null, null, "STOCK", "code", "asc", 1, 20));
        assertThat(stocks.total()).isEqualTo(4);

        MarketListResult gem = marketQueryService.list(
                MarketListQuery.of(null, "GEM", null, "code", "asc", 1, 20));
        assertThat(gem.rows()).extracting(row -> row.instrumentId())
                .containsExactly("300750.SZ");

        MarketListResult keyword = marketQueryService.list(
                MarketListQuery.of("浦发", null, null, "code", "asc", 1, 20));
        assertThat(keyword.rows()).extracting(row -> row.instrumentId())
                .containsExactly("600000.SH");
    }

    @Test
    @DisplayName("排序：成交额降序时无快照的标的排最后")
    void sortsByAmountWithNullsLast() {
        MarketListResult result = marketQueryService.list(
                MarketListQuery.of(null, null, null, "amount", "desc", 1, 20));

        assertThat(result.rows()).extracting(row -> row.instrumentId())
                .containsExactly("300750.SZ", "000001.SZ", "510300.SH", "600000.SH", "688981.SH");
        assertThat(result.rows().get(4).amount()).isNull();
    }

    @Test
    @DisplayName("分页：总数与当前页条数正确")
    void paginates() {
        MarketListResult page = marketQueryService.list(
                MarketListQuery.of(null, null, null, "code", "asc", 2, 2));

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.rows()).hasSize(2);
        assertThat(page.from()).isEqualTo(3);
        assertThat(page.to()).isEqualTo(4);
        assertThat(page.hasPrev()).isTrue();
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("详情：组装档案、快照与 K 线数据；未知标的返回空")
    void buildsStockDetail() {
        StockDetail detail = marketQueryService
                .findStockDetail("600000.SH", KlineRange.SIX_MONTHS).orElseThrow();

        assertThat(detail.instrument().name()).isEqualTo("浦发银行");
        assertThat(detail.snapshot()).isNotNull();
        assertThat(detail.latestPrice()).isEqualTo(9.26);
        assertThat(detail.latestBar().close()).isEqualTo(9.26);
        assertThat(detail.chart().dates()).hasSize(2);
        assertThat(detail.chart().candles().get(1)).containsExactly(9.26, 9.26, 9.26, 9.26);
        assertThat(detail.chart().volumes()).hasSize(2);

        assertThat(marketQueryService.findStockDetail("999999.SH", KlineRange.SIX_MONTHS)).isEmpty();
    }

    @Test
    @DisplayName("指数概览：按配置顺序返回，缺数据时为 null，涨跌幅按前收计算")
    void buildsIndexQuotes() {
        List<IndexQuote> quotes = marketQueryService.indexQuotes();

        assertThat(quotes).hasSize(4);
        assertThat(quotes.get(0).instrumentId()).isEqualTo("000001.SH");
        assertThat(quotes.get(0).close()).isEqualTo(3412.5);
        assertThat(quotes.get(0).change()).isCloseTo(12.5, within(1e-9));
        assertThat(quotes.get(0).pctChg()).isCloseTo(12.5 / 3400.0, within(1e-9));

        // 只有一根日K：有点位、无涨跌
        IndexQuote csi300 = quotes.get(3);
        assertThat(csi300.close()).isEqualTo(4012.7);
        assertThat(csi300.pctChg()).isNull();

        // 未同步的指数：整卡数据为 null
        assertThat(quotes.get(1).close()).isNull();
    }

    @Test
    @DisplayName("快照摘要：覆盖数与最新抓取时刻")
    void summarizesSnapshots() {
        MarketSnapshotSummary summary = marketQueryService.snapshotSummary();

        assertThat(summary.covered()).isEqualTo(4);
        assertThat(summary.fetchedAt()).isEqualTo(FETCHED_AT);
    }

    private MarketListQuery defaultQuery() {
        return MarketListQuery.of(null, null, null, null, null, 1, 20);
    }

    private Instrument instrument(String id, String type, String board, String name) {
        String[] parts = id.split("\\.");
        return new Instrument(id, type, parts[1], parts[0], name, board,
                "CNY", "Asia/Shanghai", null, null, Instrument.STATUS_LISTED);
    }

    private MarketSnapshot snapshot(String id, double price, double pctChg,
                                    double amount, Double pe, double totalMv) {
        return new MarketSnapshot(id, price, pctChg, 1_000_000L, amount,
                0.01, pe, 1.0, totalMv, totalMv * 0.9, "SINA_MARKET_CENTER", FETCHED_AT);
    }

    private DailyBar bar(String id, LocalDate date, double close) {
        return new DailyBar(id, date, close, close, close, close,
                null, 100_000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
