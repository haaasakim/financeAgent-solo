package org.haaasaki.financeagent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 市场页面渲染测试：验证模板与片段能真实渲染，而非只测 Controller 返回值。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:marketweb?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class MarketControllerTest {

    @Autowired
    private MockMvc mockMvc;
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
        jdbcTemplate.update("DELETE FROM sync_log");

        instrumentMapper.upsertBatch(List.of(
                new Instrument("600000.SH", "STOCK", "SH", "600000", "浦发银行", "MAIN",
                        "CNY", "Asia/Shanghai", null, null, Instrument.STATUS_LISTED)));
        marketSnapshotMapper.upsertBatch(List.of(new MarketSnapshot(
                "600000.SH", 9.26, 0.012, 1_000_000L, 6.05e8, 0.01,
                4.8, 0.42, 2.845e11, 2.8e11, "SINA_MARKET_CENTER", "2026-09-12T15:30:00")));

        LocalDate today = LocalDate.now();
        dailyBarMapper.upsertBatch(List.of(
                new DailyBar("600000.SH", today.minusDays(1), 9.20, 9.30, 9.15, 9.26,
                        null, 100_000L, null, 0.0065, null, DailyBar.ADJ_QFQ, "TEST")));
    }

    @Test
    @DisplayName("市场页：指数概览、表格与快照新鲜度正常渲染")
    void marketPageRenders() throws Exception {
        mockMvc.perform(get("/market"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("全市场标的")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("浦发银行")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("上证指数")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("600000.SH")));
    }

    @Test
    @DisplayName("表格片段：只返回片段本身，不含完整页面外壳")
    void tableFragmentRendersWithoutLayout() throws Exception {
        mockMvc.perform(get("/fragments/market/table")
                        .param("sort", "amount")
                        .param("dir", "desc")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"market-table\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("浦发银行")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<html"))));
    }

    @Test
    @DisplayName("个股详情页：行情摘要与 K 线数据注入渲染")
    void stockDetailRenders() throws Exception {
        mockMvc.perform(get("/market/stock/600000.SH"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("浦发银行")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("__KLINE__")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("前复权")));
    }

    @Test
    @DisplayName("未知标的返回 404")
    void unknownStockReturns404() throws Exception {
        mockMvc.perform(get("/market/stock/999999.SH"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("数据总览页在骨架重构后仍正常渲染")
    void dashboardStillRenders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("最近同步记录")));
    }
}
