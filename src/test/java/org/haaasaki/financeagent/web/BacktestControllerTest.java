package org.haaasaki.financeagent.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.backtest.engine.BacktestParams;
import org.haaasaki.financeagent.backtest.engine.RebalanceFrequency;
import org.haaasaki.financeagent.data.mapper.BacktestTaskMapper;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.StrategyMapper;
import org.haaasaki.financeagent.data.model.BacktestTask;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
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
 * 回测页面测试：历史任务的入参回显与默认值。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:backtestweb?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class BacktestControllerTest {

    private static final LocalDate DATA_THROUGH = LocalDate.of(2026, 9, 11);
    private static final String DSL =
            "{\"all\":[{\"factor\":\"return20\",\"op\":\"gte\",\"value\":0}],\"any\":[],\"not\":[]}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private StrategyMapper strategyMapper;
    @Autowired
    private BacktestTaskMapper backtestTaskMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM backtest_trade");
        jdbcTemplate.update("DELETE FROM backtest_daily");
        jdbcTemplate.update("DELETE FROM backtest_task");
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM instrument");
        jdbcTemplate.update("DELETE FROM strategy");

        instrumentMapper.upsertBatch(List.of(new Instrument("000300.SH", Instrument.TYPE_INDEX,
                "SH", "000300", "沪深300", "INDEX", "CNY", "Asia/Shanghai",
                LocalDate.of(2005, 1, 1), null, Instrument.STATUS_LISTED)));
        dailyBarMapper.upsertBatch(List.of(new DailyBar("000300.SH", DATA_THROUGH,
                4000, 4010, 3990, 4005, 3995.0, 1_000_000L, null, null, null,
                DailyBar.ADJ_QFQ, "TEST")));
    }

    @Test
    @DisplayName("历史回测：表单回显任务保存的入参与单位换算")
    void echoesHistoricalParams() throws Exception {
        long strategyId = saveStrategy();
        BacktestParams params = new BacktestParams(strategyId, "回显策略", "hash",
                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 12, 31), RebalanceFrequency.WEEKLY, 30,
                new BigDecimal("500000"), new BigDecimal("0.0002"), new BigDecimal("5.00"),
                new BigDecimal("0.0008"), new BigDecimal("0.0003"), "000300.SH", DATA_THROUGH);
        long taskId = insertTask(params);

        mockMvc.perform(get("/backtest").param("taskId", String.valueOf(taskId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"2024-01-02\"")))
                .andExpect(content().string(containsString("value=\"2024-12-31\"")))
                .andExpect(content().string(containsString("value=\"500000\"")))
                .andExpect(content().string(containsString("value=\"0.02\"")))
                .andExpect(content().string(containsString("value=\"0.08\"")))
                .andExpect(content().string(containsString("value=\"3\"")))
                .andExpect(content().string(containsString("value=\"WEEKLY\" selected=\"selected\"")))
                .andExpect(content().string(containsString("name=\"topN\" value=\"30\"")))
                .andExpect(content().string(containsString("id=\"bt-result\"")));
    }

    @Test
    @DisplayName("无任务参数：使用默认入参")
    void usesDefaultsWithoutTask() throws Exception {
        mockMvc.perform(get("/backtest"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"MONTHLY\" selected=\"selected\"")))
                .andExpect(content().string(containsString("name=\"topN\" value=\"20\"")))
                .andExpect(content().string(containsString("min=\"1\" max=\"100\"")))
                .andExpect(content().string(containsString("value=\"1000000\"")));
    }

    @Test
    @DisplayName("任务不存在：展示错误摘要，页面正常")
    void showsErrorForMissingTask() throws Exception {
        mockMvc.perform(get("/backtest").param("taskId", "999999"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("回测任务不存在")));
    }

    private long saveStrategy() {
        strategyMapper.upsert("回显策略", null, DSL);
        return strategyMapper.findAll().get(0).id();
    }

    private long insertTask(BacktestParams params) throws Exception {
        BacktestTask task = new BacktestTask(null, params.strategyId(), params.strategyName(),
                params.strategyHash(), "1", objectMapper.writeValueAsString(params),
                BacktestTask.STATUS_SUCCESS, 0, 0, null, DATA_THROUGH, params.benchmarkId(),
                params.initialCapital().doubleValue(), 500000.0, 0.0, 0.0, 0.0, null, null, null,
                "2026-09-13T00:00", null, null);
        backtestTaskMapper.insert(task);
        long taskId = backtestTaskMapper.lastInsertId();
        // insert 只写基础字段，绩效快照由 markSuccess 补齐，页面才能渲染指标卡
        backtestTaskMapper.markSuccess(taskId, 0, 500000.0, 0.01, 0.0, 0.0, null, null, null,
                "2026-09-13T00:01");
        return taskId;
    }
}
