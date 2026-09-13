package org.haaasaki.financeagent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;
import org.hamcrest.Matchers;
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
 * 因子验证入口渲染测试：页面与 JSON 接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:factorweb?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class FactorControllerTest {

    private static final String ID = "000001.SZ";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private FactorDailyMapper factorDailyMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM factor_daily");
        jdbcTemplate.update("DELETE FROM instrument");
        instrumentMapper.upsertBatch(List.of(new Instrument(
                ID, Instrument.TYPE_STOCK, "SZ", "000001", "平安银行",
                "MAIN", "CNY", "Asia/Shanghai", null, null, Instrument.STATUS_LISTED)));
        factorDailyMapper.upsertBatch(List.of(
                factor("2026-09-10", 11.0),
                factor("2026-09-11", 12.0)));
    }

    @Test
    @DisplayName("因子页面：标的名称、因子值与单位说明正常渲染")
    void pageRenders() throws Exception {
        mockMvc.perform(get("/market/factors").param("instrumentId", ID))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("平安银行")))
                .andExpect(content().string(Matchers.containsString("技术因子")))
                .andExpect(content().string(Matchers.containsString("2026-09-11")))
                .andExpect(content().string(Matchers.containsString("12.000")));
    }

    @Test
    @DisplayName("因子页面：未知标的展示空状态")
    void pageShowsEmptyState() throws Exception {
        mockMvc.perform(get("/market/factors").param("instrumentId", "999999.SH"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("暂无因子数据")));
    }

    @Test
    @DisplayName("JSON 接口：按交易日倒序返回，limit 生效")
    void apiReturnsLatestFirst() throws Exception {
        mockMvc.perform(get("/api/factors/{instrumentId}", ID).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tradeDate").value("2026-09-11"))
                .andExpect(jsonPath("$[0].ma5").value(12.0))
                .andExpect(jsonPath("$[1].tradeDate").value("2026-09-10"));
    }

    @Test
    @DisplayName("JSON 接口：未知标的返回空数组")
    void apiReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/factors/{instrumentId}", "999999.SH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private FactorDaily factor(String date, double close) {
        return new FactorDaily(ID, LocalDate.parse(date),
                close, close, close, close,
                close, close, 0.0,
                50.0, 50.0, 50.0,
                0.1, 0.2, 0.01, -0.05);
    }
}
