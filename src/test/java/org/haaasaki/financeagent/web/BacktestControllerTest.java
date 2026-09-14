package org.haaasaki.financeagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.backtest.engine.BacktestParams;
import org.haaasaki.financeagent.backtest.engine.RebalanceFrequency;
import org.haaasaki.financeagent.data.mapper.BacktestDailyMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTaskMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTradeMapper;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.StrategyMapper;
import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTask;
import org.haaasaki.financeagent.data.model.BacktestTrade;
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
 * 回测页面测试：历史任务的入参回显、默认值、配对视图搜索与回测记录分页排序。
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
    private BacktestDailyMapper backtestDailyMapper;
    @Autowired
    private BacktestTradeMapper backtestTradeMapper;
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
                new BigDecimal("0.0008"), new BigDecimal("0.0003"),
                new BigDecimal("0.12"), 40, "000300.SH", DATA_THROUGH);
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
                .andExpect(content().string(containsString("name=\"stopLossPct\" value=\"12\"")))
                .andExpect(content().string(containsString("name=\"timeStopDays\" value=\"40\"")))
                .andExpect(content().string(containsString("id=\"bt-result\"")))
                .andExpect(content().string(containsString("id=\"bt-position-chart\"")))
                .andExpect(content().string(containsString("持仓比曲线")));
    }

    @Test
    @DisplayName("无任务参数：使用默认入参")
    void usesDefaultsWithoutTask() throws Exception {
        mockMvc.perform(get("/backtest"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"MONTHLY\" selected=\"selected\"")))
                .andExpect(content().string(containsString("name=\"topN\" value=\"20\"")))
                .andExpect(content().string(containsString("min=\"1\" max=\"100\"")))
                .andExpect(content().string(containsString("name=\"stopLossPct\" value=\"12\"")))
                .andExpect(content().string(containsString("name=\"timeStopDays\" value=\"0\"")))
                .andExpect(content().string(containsString("value=\"1000000\"")));
    }

    @Test
    @DisplayName("任务不存在：展示错误摘要，页面正常")
    void showsErrorForMissingTask() throws Exception {
        mockMvc.perform(get("/backtest").param("taskId", "999999"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("回测任务不存在")));
    }

    @Test
    @DisplayName("运行中任务：刷新可由 taskId 恢复进度并继续轮询")
    void restoresPendingTaskProgress() throws Exception {
        BacktestParams params = successParams(saveStrategy());
        BacktestTask pending = new BacktestTask(null, params.strategyId(), params.strategyName(),
                params.strategyHash(), "1", objectMapper.writeValueAsString(params),
                BacktestTask.STATUS_PENDING, 0, 0, null, DATA_THROUGH, params.benchmarkId(),
                params.initialCapital().doubleValue(), null, null, null, null, null, null, null,
                "2026-09-13T00:00", null, null);
        backtestTaskMapper.insert(pending);
        long taskId = backtestTaskMapper.lastInsertId();

        mockMvc.perform(get("/backtest").param("taskId", String.valueOf(taskId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"bt-progress\"")))
                .andExpect(content().string(containsString("任务 #" + taskId)))
                .andExpect(content().string(containsString("hx-trigger=\"every 2s\"")));
    }

    @Test
    @DisplayName("配对视图搜索：关键词与卖出日过滤，费用合计随命中集合收窄")
    void filtersRoundTripsByKeywordAndSellDate() throws Exception {
        long taskId = insertSuccessTask(saveStrategy());
        backtestTradeMapper.batchInsert(List.of(
                trade(taskId, 1, "000001.SZ", "000001", "平安银行", "BUY",
                        LocalDate.of(2024, 1, 2), 10.0, 1000L, 10000.0, 5.0, null),
                trade(taskId, 2, "000001.SZ", "000001", "平安银行", "SELL",
                        LocalDate.of(2024, 1, 5), 11.0, 1000L, 11000.0, 10.0, 985.0),
                trade(taskId, 3, "600519.SH", "600519", "贵州茅台", "BUY",
                        LocalDate.of(2024, 2, 1), 1700.0, 100L, 170000.0, 51.0, null)));
        backtestDailyMapper.batchInsert(List.of(
                daily(taskId, LocalDate.of(2024, 1, 2)), daily(taskId, LocalDate.of(2024, 1, 3)),
                daily(taskId, LocalDate.of(2024, 1, 4)), daily(taskId, LocalDate.of(2024, 1, 5)),
                daily(taskId, LocalDate.of(2024, 2, 1))));

        mockMvc.perform(get("/fragments/backtest/{id}/trips", taskId).param("keyword", "平安"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("平安银行")))
                .andExpect(content().string(not(containsString("600519"))))
                .andExpect(content().string(containsString("筛选命中 1 笔往返")))
                .andExpect(content().string(containsString("手续费合计 ¥15.00")));

        // 卖出日条件排除期末持有，只剩已平仓的一条
        mockMvc.perform(get("/fragments/backtest/{id}/trips", taskId)
                        .param("sellFrom", "2024-01-01").param("sellTo", "2024-01-31"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("000001")))
                .andExpect(content().string(not(containsString("600519"))))
                .andExpect(content().string(containsString("筛选命中 1 笔往返")));

        mockMvc.perform(get("/fragments/backtest/{id}/trips", taskId).param("buyFrom", "2030-01-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("没有符合条件的往返记录")));
    }

    @Test
    @DisplayName("回测记录：全部任务展示，支持收益与回撤排序，未知键回退编号降序")
    void listsAllTasksWithSort() throws Exception {
        long strategyId = saveStrategy();
        // 先插入高收益（id 较小），再插入低收益（id 较大）
        insertTask(successParams(strategyId), 0.80, -0.20);
        insertTask(successParams(strategyId), 0.10, -0.50);

        // 默认编号降序：最新（低收益）在前；关键参数列展示调仓频率 / 持仓数 / 回测区间
        mockMvc.perform(get("/fragments/backtest/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("共 2 条")))
                .andExpect(content().string(containsString("调仓频率")))
                .andExpect(content().string(containsString("持仓数")))
                .andExpect(content().string(containsString("回测区间")))
                .andExpect(content().string(containsString("每日")))
                .andExpect(content().string(containsString("2024-01-01 ~ 2024-02-29")))
                .andExpect(content().string(stringContainsInOrder("10.00%", "80.00%")));

        // 累计收益降序：高收益在前
        mockMvc.perform(get("/fragments/backtest/tasks")
                        .param("sort", "cumulativeReturn").param("dir", "desc"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Push-Url",
                        "/backtest?page=1&sort=cumulativeReturn&dir=desc"))
                .andExpect(content().string(containsString("sorted")))
                .andExpect(content().string(stringContainsInOrder("80.00%", "10.00%")));

        // 最大回撤降序：回撤小（-20%）在前
        mockMvc.perform(get("/fragments/backtest/tasks")
                        .param("sort", "maxDrawdown").param("dir", "desc"))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder("-20.00%", "-50.00%")));

        // 未知排序键回退编号降序
        mockMvc.perform(get("/fragments/backtest/tasks").param("sort", "evil"))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder("10.00%", "80.00%")));
    }

    @Test
    @DisplayName("回测记录分页：每页 20 条，第二页返回剩余记录")
    void pagesAllTasks() throws Exception {
        long strategyId = saveStrategy();
        for (int i = 0; i < 21; i++) {
            insertTask(successParams(strategyId), i / 100.0, -0.10);
        }

        String page1 = mockMvc.perform(get("/fragments/backtest/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("共 21 条")))
                .andExpect(content().string(containsString("第 1 / 2 页")))
                .andReturn().getResponse().getContentAsString();
        assertThat(countOccurrences(page1, ">查看</a>")).isEqualTo(20);

        String page2 = mockMvc.perform(get("/fragments/backtest/tasks").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("第 2 / 2 页")))
                .andReturn().getResponse().getContentAsString();
        assertThat(countOccurrences(page2, ">查看</a>")).isEqualTo(1);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private BacktestTrade trade(long taskId, long orderNo, String instrumentId, String code,
                                String name, String side, LocalDate filledDate, Double price,
                                Long shares, Double amount, Double fee, Double pnl) {
        return new BacktestTrade(taskId, orderNo, instrumentId, code, name, side,
                BacktestTrade.STATUS_FILLED, filledDate.minusDays(1), filledDate, filledDate,
                price, shares, amount, fee, pnl, null);
    }

    private BacktestDaily daily(long taskId, LocalDate tradeDate) {
        return new BacktestDaily(taskId, tradeDate, 500000.0, 500000.0, 0.0, 500000.0, 0.0);
    }

    private long insertSuccessTask(long strategyId) throws Exception {
        return insertTask(successParams(strategyId));
    }

    private BacktestParams successParams(long strategyId) {
        return new BacktestParams(strategyId, "配对策略", "hash",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 29), RebalanceFrequency.DAILY, 10,
                new BigDecimal("1000000"), new BigDecimal("0.0003"), new BigDecimal("5.00"),
                new BigDecimal("0.0005"), new BigDecimal("0.0005"),
                null, 0, "000300.SH", DATA_THROUGH);
    }

    private long saveStrategy() {
        strategyMapper.upsert("回显策略", null, DSL);
        return strategyMapper.findAll().get(0).id();
    }

    private long insertTask(BacktestParams params) throws Exception {
        return insertTask(params, 0.01, 0.0);
    }

    private long insertTask(BacktestParams params, double cumulativeReturn, double maxDrawdown)
            throws Exception {
        BacktestTask task = new BacktestTask(null, params.strategyId(), params.strategyName(),
                params.strategyHash(), "1", objectMapper.writeValueAsString(params),
                BacktestTask.STATUS_SUCCESS, 0, 0, null, DATA_THROUGH, params.benchmarkId(),
                params.initialCapital().doubleValue(), 500000.0, 0.0, 0.0, 0.0, null, null, null,
                "2026-09-13T00:00", null, null);
        backtestTaskMapper.insert(task);
        long taskId = backtestTaskMapper.lastInsertId();
        // insert 只写基础字段，绩效快照由 markSuccess 补齐，页面才能渲染指标卡
        backtestTaskMapper.markSuccess(taskId, 0, 500000.0, cumulativeReturn, 0.0, maxDrawdown,
                null, null, null, "2026-09-13T00:01");
        return taskId;
    }
}
