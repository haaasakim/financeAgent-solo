package org.haaasaki.financeagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.MarketSnapshotMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.MarketSnapshot;
import org.haaasaki.financeagent.screener.ScreenerService;
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
 * 选股页面与 HTMX 片段渲染测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:screenerweb?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class ScreenerControllerTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 11);
    private static final String VALID_DSL =
            "{\"all\":[{\"factor\":\"peTtm\",\"op\":\"lte\",\"value\":30}],\"any\":[],\"not\":[]}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ScreenerService screenerService;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private MarketSnapshotMapper marketSnapshotMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private FactorDailyMapper factorDailyMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM factor_daily");
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM market_snapshot");
        jdbcTemplate.update("DELETE FROM instrument");
        jdbcTemplate.update("DELETE FROM strategy");

        instrumentMapper.upsertBatch(List.of(
                instrument("000001.SZ", "平安银行"),
                instrument("600000.SH", "浦发银行")));
        marketSnapshotMapper.upsertBatch(List.of(
                snapshot("000001.SZ", 5.0, 0.6),
                snapshot("600000.SH", 40.0, 4.5)));
        dailyBarMapper.upsertBatch(List.of(
                bar("000001.SZ", 11.0),
                bar("600000.SH", 9.0)));
        factorDailyMapper.upsertBatch(List.of(
                factor("000001.SZ", 0.10),
                factor("600000.SH", -0.05)));
    }

    @Test
    @DisplayName("页面：因子目录含灰显占位，预设策略与空策略列表正常渲染")
    void pageRendersCatalogAndPresets() throws Exception {
        mockMvc.perform(get("/screener"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("因子目录")))
                .andExpect(content().string(containsString("筛选条件")))
                .andExpect(content().string(containsString("低估值价值")))
                .andExpect(content().string(containsString("趋势向上")))
                .andExpect(content().string(containsString("超跌企稳")))
                .andExpect(content().string(containsString("待财报数据源，暂不可用")))
                .andExpect(content().string(containsString("尚未保存策略")))
                // 未加载已保存策略时「拿去回测」置灰，并提示先保存
                .andExpect(content().string(containsString("先保存为策略后再回测")))
                // 条件行下拉选项必须服务端渲染，否则 Alpine x-model 会因选项未就绪选中首项
                .andExpect(content().string(containsString("<option value=\"peTtm\">PE-TTM</option>")))
                .andExpect(content().string(containsString("<option value=\"lte\">≤</option>")));
    }

    @Test
    @DisplayName("页面：template 参数套用预设模板并提示来源")
    void pageAppliesPresetTemplate() throws Exception {
        mockMvc.perform(get("/screener").param("template", "trendUp"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已套用模板：趋势向上")));

        mockMvc.perform(get("/screener").param("template", "notExists"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("已套用模板"))));
    }

    @Test
    @DisplayName("页面：strategyId 加载已保存策略，未知 ID 提示重置")
    void pageLoadsSavedStrategy() throws Exception {
        screenerService.saveStrategy("我的策略", null, VALID_DSL);
        long id = screenerService.listStrategies().get(0).id();

        mockMvc.perform(get("/screener").param("strategyId", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已加载策略：我的策略")));

        mockMvc.perform(get("/screener").param("strategyId", "999999"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("策略不存在，已重置条件")));
    }

    @Test
    @DisplayName("结果片段：命中标的、评分与分页信息渲染，链接指向行情详情")
    void resultsFragmentRendersRows() throws Exception {
        mockMvc.perform(post("/fragments/screener/results").param("dsl", VALID_DSL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("平安银行")))
                .andExpect(content().string(containsString("评分")))
                .andExpect(content().string(containsString("100.0")))
                .andExpect(content().string(containsString("共 1 条")))
                .andExpect(content().string(containsString("第 1 / 1 页")))
                .andExpect(content().string(containsString("/market/stock/000001.SZ")));
    }

    @Test
    @DisplayName("结果片段：条件无效时返回错误摘要而非堆栈")
    void resultsFragmentShowsErrors() throws Exception {
        mockMvc.perform(post("/fragments/screener/results")
                        .param("dsl", "{\"all\":[{\"factor\":\"nope\",\"op\":\"gte\",\"value\":1}],"
                                + "\"any\":[],\"not\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("存在未知因子：nope")));

        mockMvc.perform(post("/fragments/screener/results").param("dsl", "{bad"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("条件树格式错误")));

        mockMvc.perform(post("/fragments/screener/results")
                        .param("dsl", "{\"all\":[],\"any\":[],\"not\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请至少设置一个条件")));
    }

    @Test
    @DisplayName("结果片段：命中超过 5,000 只展示计数与收紧提示")
    void resultsFragmentShowsExceededNotice() throws Exception {
        List<Object[]> batch = new ArrayList<>();
        for (int i = 1; i <= 5_000; i++) {
            String code = String.format("8%05d", i);
            batch.add(new Object[]{code + ".SZ", Instrument.TYPE_STOCK, "SZ", code, "批量" + i,
                    "MAIN", "CNY", "Asia/Shanghai", "2000-01-01", null, Instrument.STATUS_LISTED,
                    "2026-01-01T00:00:00", "2026-01-01T00:00:00"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO instrument (instrument_id, instrument_type, exchange,"
                + " code, name, board, currency, timezone, listed_at, delisted_at, status,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch);

        mockMvc.perform(post("/fragments/screener/results")
                        .param("dsl", "{\"all\":[{\"factor\":\"excludeSt\",\"op\":\"eq\",\"value\":1}],"
                                + "\"any\":[],\"not\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("匹配 5002 条")))
                .andExpect(content().string(containsString("超过 5,000 条上限")))
                .andExpect(content().string(not(containsString("<td class=\"code\">"))));
    }

    @Test
    @DisplayName("策略片段：保存成功刷新列表，重名覆盖，名称校验")
    void strategyFragmentSavesAndLists() throws Exception {
        mockMvc.perform(post("/fragments/screener/strategies")
                        .param("name", "低估值")
                        .param("description", "PE 低")
                        .param("dsl", VALID_DSL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已保存策略：低估值")))
                .andExpect(content().string(containsString("低估值")))
                .andExpect(content().string(containsString("PE 低")))
                .andExpect(content().string(containsString("/screener?strategyId=")));

        mockMvc.perform(post("/fragments/screener/strategies")
                        .param("name", "低估值")
                        .param("description", "PE 低（覆盖）")
                        .param("dsl", VALID_DSL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PE 低（覆盖）")));
        assertThat(screenerService.listStrategies()).hasSize(1);

        mockMvc.perform(post("/fragments/screener/strategies")
                        .param("name", "  ")
                        .param("dsl", VALID_DSL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("策略名不能为空")));
    }

    @Test
    @DisplayName("策略片段：删除后列表刷新为空态")
    void strategyFragmentDeletes() throws Exception {
        screenerService.saveStrategy("待删除", null, VALID_DSL);
        long id = screenerService.listStrategies().get(0).id();

        mockMvc.perform(post("/fragments/screener/strategies/{id}/delete", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已删除策略")))
                .andExpect(content().string(containsString("尚未保存策略")))
                .andExpect(content().string(not(containsString("待删除"))));
    }

    private Instrument instrument(String id, String name) {
        String[] parts = id.split("\\.");
        return new Instrument(id, Instrument.TYPE_STOCK, parts[1], parts[0], name, "MAIN",
                "CNY", "Asia/Shanghai", LocalDate.of(2000, 1, 1), null, Instrument.STATUS_LISTED);
    }

    private MarketSnapshot snapshot(String id, Double pe, Double pb) {
        return new MarketSnapshot(id, 10.0, 0.0, 1_000L, 1.0e6, 0.01,
                pe, pb, 1.0e9, 0.8e9, "TEST", "2026-09-12T15:30:00");
    }

    private DailyBar bar(String id, double close) {
        return new DailyBar(id, TRADE_DATE, close, close, close, close,
                null, 100_000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }

    private FactorDaily factor(String id, double return20) {
        return new FactorDaily(id, TRADE_DATE, 10.0, 10.0, 10.0, 10.0,
                0.0, 0.0, 0.0, 50.0, 50.0, 50.0,
                return20, return20, 0.01, -0.05);
    }
}
