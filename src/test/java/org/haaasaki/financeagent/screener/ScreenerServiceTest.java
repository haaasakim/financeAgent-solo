package org.haaasaki.financeagent.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.MarketSnapshotMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.MarketSnapshot;
import org.haaasaki.financeagent.data.model.ScreenerRow;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.dsl.CompareOp;
import org.haaasaki.financeagent.screener.dsl.Condition;
import org.haaasaki.financeagent.screener.dsl.ScoreDirection;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 选股服务测试：真实 SQLite 临时库验证条件树求值、边界校验与策略持久化。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:screenertest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class ScreenerServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 11);
    private static final String FETCHED_AT = "2026-09-12T15:30:00";

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
                instrument("000001.SZ", "MAIN", "平安银行", Instrument.TYPE_STOCK),
                instrument("600000.SH", "MAIN", "浦发银行", Instrument.TYPE_STOCK),
                instrument("300750.SZ", "GEM", "宁德时代", Instrument.TYPE_STOCK),
                instrument("688981.SH", "STAR", "中芯国际", Instrument.TYPE_STOCK),
                instrument("510300.SH", "ETF", "沪深300ETF", Instrument.TYPE_ETF),
                instrument("002594.SZ", "MAIN", "ST测试", Instrument.TYPE_STOCK),
                instrument("600519.SH", "MAIN", "贵州茅台", Instrument.TYPE_STOCK),
                instrument("000300.SH", "INDEX", "沪深300", Instrument.TYPE_INDEX)));

        marketSnapshotMapper.upsertBatch(List.of(
                snapshot("000001.SZ", 5.0, 0.6),
                snapshot("600000.SH", 40.0, 4.5),
                snapshot("300750.SZ", 25.0, 5.0),
                snapshot("688981.SH", 60.0, 6.0),
                snapshot("510300.SH", null, 1.2),
                snapshot("002594.SZ", 10.0, 1.0),
                snapshot("600519.SH", 50.0, 10.0)));

        dailyBarMapper.upsertBatch(List.of(
                bar("000001.SZ", TRADE_DATE.minusDays(1), 11.0),
                bar("000001.SZ", TRADE_DATE, 12.0),
                bar("600000.SH", TRADE_DATE, 9.0),
                bar("300750.SZ", TRADE_DATE, 200.0),
                bar("688981.SH", TRADE_DATE, 80.0),
                bar("510300.SH", TRADE_DATE, 4.0),
                bar("002594.SZ", TRADE_DATE, 250.0),
                bar("600519.SH", TRADE_DATE.minusDays(10), 1500.0),
                bar("000300.SH", TRADE_DATE, 4000.0)));

        factorDailyMapper.upsertBatch(List.of(
                factor("000001.SZ", 11.0, 10.0, 0.10, 0.20, 0.020, -0.10),
                factor("600000.SH", 9.0, 15.0, -0.05, -0.10, 0.050, -0.50),
                factor("300750.SZ", 180.0, 100.0, 0.30, 0.40, 0.040, 0.00),
                factor("688981.SH", 70.0, 100.0, 0.01, 0.02, 0.010, -0.60),
                factor("510300.SH", 3.9, 3.5, 0.02, 0.05, 0.008, -0.05),
                factor("002594.SZ", 200.0, 100.0, 0.50, 0.30, 0.060, 0.00)));
    }

    @Test
    @DisplayName("parse：空串与非法 JSON 抛用户可见异常；合法 DSL 序列化往返一致")
    void parseHandlesBadAndGoodInput() {
        assertThatThrownBy(() -> screenerService.parse(null))
                .isInstanceOf(BusinessException.class).hasMessage("条件树为空");
        assertThatThrownBy(() -> screenerService.parse("  "))
                .isInstanceOf(BusinessException.class).hasMessage("条件树为空");
        assertThatThrownBy(() -> screenerService.parse("{"))
                .isInstanceOf(BusinessException.class).hasMessage("条件树格式错误");

        ScreenerDsl dsl = new ScreenerDsl(
                List.of(c("peTtm", CompareOp.LTE, 30)),
                List.of(c("aboveMa60", CompareOp.EQ, 1)),
                List.of(c("return20", CompareOp.GT, 0)));
        assertThat(screenerService.parse(screenerService.toJson(dsl))).isEqualTo(dsl);
    }

    @Test
    @DisplayName("校验：未知因子 / 灰显因子 / 布尔运算符 / 布尔取值 / 非有限值 / 越界值")
    void validationRejectsInvalidConditions() {
        assertThatThrownBy(() -> screenerService.screen(all(c("nope", CompareOp.GTE, 1)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未知因子：nope");
        assertThatThrownBy(() -> screenerService.screen(all(c("dividendYield", CompareOp.GTE, 1)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("暂不可用");
        assertThatThrownBy(() -> screenerService.screen(all(c("aboveMa60", CompareOp.GT, 1)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只支持");
        assertThatThrownBy(() -> screenerService.screen(all(c("aboveMa60", CompareOp.EQ, 0.5)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("取值只能是");
        assertThatThrownBy(() -> screenerService.screen(
                all(new Condition("peTtm", CompareOp.GTE, null)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("缺少有效阈值");
        assertThatThrownBy(() -> screenerService.screen(
                all(new Condition("peTtm", CompareOp.GTE, Double.NaN)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("缺少有效阈值");
        assertThatThrownBy(() -> screenerService.screen(all(c("peTtm", CompareOp.LTE, 1_000_001)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超出允许范围");
    }

    @Test
    @DisplayName("校验：空条件树、单组上限 20、总数上限 50")
    void validationRejectsSizeLimits() {
        assertThatThrownBy(() -> screenerService.screen(ScreenerDsl.empty(), 1))
                .isInstanceOf(BusinessException.class).hasMessage("请至少设置一个条件");

        List<Condition> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(c("peTtm", CompareOp.LTE, 30));
        }
        assertThatThrownBy(() -> screenerService.screen(
                new ScreenerDsl(twentyOne, List.of(), List.of()), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过 20 个");

        List<Condition> twenty = twentyOne.subList(0, 20);
        List<Condition> eleven = twentyOne.subList(0, 11);
        assertThatThrownBy(() -> screenerService.screen(
                new ScreenerDsl(twenty, twenty, eleven), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("条件总数不能超过 50 个");
    }

    @Test
    @DisplayName("ALL 组：所有条件同时成立；指数不进结果")
    void screensWithAllGroup() {
        ScreenerResult result = screenerService.screen(all(
                c("peTtm", CompareOp.LTE, 30),
                c("pb", CompareOp.LTE, 3)), 1);

        assertThat(result.total()).isEqualTo(2);
        assertThat(ids(result)).containsExactly("000001.SZ", "002594.SZ");
    }

    @Test
    @DisplayName("ANY 组：至少一个条件成立，布尔因子按 0/1 求值；评分只计满足的数值条件")
    void screensWithAnyGroup() {
        ScreenerResult result = screenerService.screen(new ScreenerDsl(
                List.of(),
                List.of(c("excludeSt", CompareOp.EQ, 0), c("peTtm", CompareOp.GT, 45)),
                List.of()), 1);

        assertThat(ids(result)).containsExactly("688981.SH", "600519.SH", "002594.SZ");
        assertThat(scores(result)).containsExactly(100.0, 50.0, null);
    }

    @Test
    @DisplayName("NOT 组：排除同时满足组内全部条件的标的，NULL 不满足条件")
    void screensWithNotGroup() {
        ScreenerResult result = screenerService.screen(new ScreenerDsl(
                List.of(), List.of(),
                List.of(c("return20", CompareOp.GT, 2), c("aboveMa60", CompareOp.EQ, 1))), 1);

        assertThat(ids(result)).containsExactly("510300.SH", "600000.SH", "600519.SH", "688981.SH");
    }

    @Test
    @DisplayName("基础因子：tradable 识别最近在交易，excludeSt 识别 ST 名称")
    void screensWithBaseFactors() {
        ScreenerResult tradable = screenerService.screen(all(c("tradable", CompareOp.EQ, 1)), 1);
        assertThat(tradable.total()).isEqualTo(6);
        assertThat(ids(tradable)).doesNotContain("600519.SH");

        ScreenerResult st = screenerService.screen(all(c("excludeSt", CompareOp.EQ, 0)), 1);
        assertThat(ids(st)).containsExactly("002594.SZ");
    }

    @Test
    @DisplayName("单位换算：百分比因子按展示单位比较，小数存值乘以 100；按评分排序")
    void screensWithPercentUnit() {
        ScreenerResult result = screenerService.screen(
                all(c("return20", CompareOp.GT, 5)), 1);

        assertThat(ids(result)).containsExactly("002594.SZ", "300750.SZ", "000001.SZ");
        assertThat(scores(result)).containsExactly(100.0, 50.0, 0.0);
    }

    @Test
    @DisplayName("距 52 周高点：负百分比阈值正确换算，越超跌评分越高")
    void screensWithPctFromHigh() {
        ScreenerResult result = screenerService.screen(
                all(c("pctFrom52wHigh", CompareOp.LTE, -30)), 1);

        assertThat(ids(result)).containsExactly("688981.SH", "600000.SH");
        assertThat(scores(result)).containsExactly(100.0, 0.0);
    }

    @Test
    @DisplayName("因子缺失（NULL）的行不匹配任何数值条件")
    void nullFactorNeverMatches() {
        ScreenerResult result = screenerService.screen(all(c("ma20GtMa60", CompareOp.EQ, 1)), 1);

        assertThat(ids(result)).containsExactly("000001.SZ", "002594.SZ", "300750.SZ", "510300.SH");
    }

    @Test
    @DisplayName("评分：命中集内相对百分位，越大越好因子按小于自身的占比")
    void ranksByRelativePercentile() {
        ScreenerResult result = screenerService.screen(all(c("return20", CompareOp.GT, 0)), 1);

        assertThat(ids(result)).containsExactly(
                "002594.SZ", "300750.SZ", "000001.SZ", "510300.SH", "688981.SH");
        assertThat(scores(result)).containsExactly(100.0, 75.0, 50.0, 25.0, 0.0);
    }

    @Test
    @DisplayName("评分：多条件取平均，越小越好因子按大于自身的占比")
    void averagesScoresAcrossConditions() {
        ScreenerResult result = screenerService.screen(all(
                c("return20", CompareOp.GT, 0),
                c("peTtm", CompareOp.LTE, 30)), 1);

        assertThat(ids(result)).containsExactly("002594.SZ", "000001.SZ", "300750.SZ");
        assertThat(scores(result)).containsExactly(75.0, 50.0, 25.0);
    }

    @Test
    @DisplayName("评分：单一命中得满分；布尔策略无评分且按代码排序")
    void singleAndBooleanScores() {
        ScreenerResult single = screenerService.screen(all(c("peTtm", CompareOp.LTE, 5)), 1);
        assertThat(ids(single)).containsExactly("000001.SZ");
        assertThat(scores(single)).containsExactly(100.0);

        ScreenerResult booleans = screenerService.screen(all(c("tradable", CompareOp.EQ, 1)), 1);
        assertThat(ids(booleans)).containsExactly(
                "000001.SZ", "002594.SZ", "300750.SZ", "510300.SH", "600000.SH", "688981.SH");
        assertThat(scores(booleans)).containsOnlyNulls();
    }

    @Test
    @DisplayName("分页：固定每页 50 条，总数 / 页码 / 边界正确")
    void paginatesAtFifty() {
        List<Instrument> extra = new ArrayList<>();
        for (int i = 1; i <= 55; i++) {
            String code = String.format("9000%02d", i);
            extra.add(new Instrument(code + ".SZ", Instrument.TYPE_STOCK, "SZ", code,
                    "测试" + i, "MAIN", "CNY", "Asia/Shanghai",
                    null, null, Instrument.STATUS_LISTED));
        }
        instrumentMapper.upsertBatch(extra);

        ScreenerResult page1 = screenerService.screen(all(c("excludeSt", CompareOp.EQ, 1)), 1);
        assertThat(page1.total()).isEqualTo(61);
        assertThat(page1.rows()).hasSize(50);
        assertThat(page1.totalPages()).isEqualTo(2);
        assertThat(page1.from()).isEqualTo(1);
        assertThat(page1.to()).isEqualTo(50);
        assertThat(page1.hasPrev()).isFalse();
        assertThat(page1.hasNext()).isTrue();

        ScreenerResult page2 = screenerService.screen(all(c("excludeSt", CompareOp.EQ, 1)), 2);
        assertThat(page2.rows()).hasSize(11);
        assertThat(page2.from()).isEqualTo(51);
        assertThat(page2.to()).isEqualTo(61);
        assertThat(page2.hasPrev()).isTrue();
        assertThat(page2.hasNext()).isFalse();

        ScreenerResult beyond = screenerService.screen(all(c("excludeSt", CompareOp.EQ, 1)), 99);
        assertThat(beyond.rows()).isEmpty();
        assertThat(beyond.total()).isEqualTo(61);
    }

    @Test
    @DisplayName("历史回看：按指定交易日截面取数与评分，无当日因子行的标的（停牌）不参与")
    void screenAtUsesExactCrossSection() {
        LocalDate history = TRADE_DATE.minusDays(300);
        seedHistory(history);

        ScreenerResult result = screenerService.screenAt(
                all(c("return20", CompareOp.GT, 0)), history, 1);
        assertThat(result.total()).isEqualTo(1);
        assertThat(ids(result)).containsExactly("000001.SZ");
        assertThat(scores(result)).containsExactly(100.0);
        assertThat(result.rows().get(0).lastPrice()).isEqualTo(8.0);

        ScreenerResult latest = screenerService.screen(all(c("return20", CompareOp.GT, 0)), 1);
        assertThat(latest.total()).isGreaterThan(result.total());
    }

    @Test
    @DisplayName("历史回看：占位因子（无历史数据源）被拒绝")
    void screenAtRejectsFactorsWithoutHistory() {
        assertThatThrownBy(() -> screenerService.screenAt(
                all(c("dividendYield", CompareOp.GTE, 1)), TRADE_DATE, 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("暂不可用");
    }

    @Test
    @DisplayName("回看日期解析：非交易日回退到最近交易日，超范围报错")
    void resolveAsOfSnapsToLatestTradingDay() {
        LocalDate history = TRADE_DATE.minusDays(300);
        seedHistory(history);

        assertThat(screenerService.resolveAsOf(TRADE_DATE)).isEqualTo(TRADE_DATE);
        assertThat(screenerService.resolveAsOf(TRADE_DATE.plusDays(1))).isEqualTo(TRADE_DATE);
        assertThat(screenerService.resolveAsOf(history)).isEqualTo(history);
        assertThatThrownBy(() -> screenerService.resolveAsOf(TRADE_DATE.minusYears(10)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有数据");
    }

    @Test
    @DisplayName("数据范围：最早 / 最新因子交易日来自本地数据")
    void exposesFactorDateRange() {
        LocalDate history = TRADE_DATE.minusDays(300);
        assertThat(screenerService.dataStartDate()).isEqualTo(TRADE_DATE);
        seedHistory(history);
        assertThat(screenerService.dataStartDate()).isEqualTo(history);
        assertThat(screenerService.dataEndDate()).isEqualTo(TRADE_DATE);
    }

    @Test
    @DisplayName("基础因子：仅股票排除 ETF，页面与历史截面口径一致")
    void stockOnlyExcludesEtf() {
        ScreenerResult result = screenerService.screen(all(c("stockOnly", CompareOp.EQ, 1)), 1);
        assertThat(ids(result)).containsExactly(
                "000001.SZ", "002594.SZ", "300750.SZ", "600000.SH", "600519.SH", "688981.SH");

        LocalDate history = TRADE_DATE.minusDays(300);
        seedHistory(history);
        assertThat(screenerService.evaluateAt(all(c("stockOnly", CompareOp.EQ, 1)), history, 10))
                .extracting(ScreenerRow::instrumentId)
                .containsExactly("000001.SZ", "600000.SH");
    }

    @Test
    @DisplayName("策略保存：同名视为覆盖更新，加载与删除正常")
    void savesAndLoadsStrategy() {
        String dslJson = screenerService.toJson(all(c("peTtm", CompareOp.LTE, 30)));
        screenerService.saveStrategy("低估值", "测试用", dslJson);

        List<Strategy> saved = screenerService.listStrategies();
        assertThat(saved).hasSize(1);
        long id = saved.get(0).id();
        assertThat(saved.get(0).name()).isEqualTo("低估值");
        assertThat(screenerService.findStrategy(id).dsl()).isEqualTo(dslJson);

        String updatedJson = screenerService.toJson(all(c("pb", CompareOp.LTE, 3)));
        screenerService.saveStrategy("低估值", "更新", updatedJson);

        List<Strategy> afterUpdate = screenerService.listStrategies();
        assertThat(afterUpdate).hasSize(1);
        assertThat(afterUpdate.get(0).id()).isEqualTo(id);
        assertThat(afterUpdate.get(0).description()).isEqualTo("更新");
        assertThat(afterUpdate.get(0).dsl()).isEqualTo(updatedJson);

        screenerService.deleteStrategy(id);
        assertThat(screenerService.listStrategies()).isEmpty();
        assertThatThrownBy(() -> screenerService.findStrategy(id))
                .isInstanceOf(BusinessException.class).hasMessage("策略不存在");
    }

    @Test
    @DisplayName("策略保存：名称与条件树必须有效，失败不落库")
    void saveStrategyValidatesInput() {
        String dslJson = screenerService.toJson(all(c("peTtm", CompareOp.LTE, 30)));

        assertThatThrownBy(() -> screenerService.saveStrategy("  ", null, dslJson))
                .isInstanceOf(BusinessException.class).hasMessage("策略名不能为空");
        assertThatThrownBy(() -> screenerService.saveStrategy("x".repeat(51), null, dslJson))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过 50");
        assertThatThrownBy(() -> screenerService.saveStrategy("坏条件", null,
                "{\"all\":[{\"factor\":\"nope\",\"op\":\"gte\",\"value\":1}]}"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未知因子");
        assertThat(screenerService.listStrategies()).isEmpty();
    }

    @Test
    @DisplayName("匹配总数超过 5,000：仅返回计数，不下发明细")
    void capsMatchesOverFiveThousand() {
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

        ScreenerResult result = screenerService.screen(all(c("excludeSt", CompareOp.EQ, 1)), 1);

        assertThat(result.total()).isEqualTo(5_006);
        assertThat(result.exceeded()).isTrue();
        assertThat(result.rows()).isEmpty();
    }

    @Test
    @DisplayName("WI-011 显式评分：权重 3:1 加权，排序与等权旧口径不同")
    void explicitScoringAppliesWeights() {
        ScreenerResult result = screenerService.screen(all(
                cs("return20", CompareOp.GT, 0, true, 3.0, ScoreDirection.HIGHER),
                cs("volatility20", CompareOp.LTE, 6, true, 1.0, ScoreDirection.LOWER)), 1);

        assertThat(ids(result)).containsExactly(
                "002594.SZ", "300750.SZ", "000001.SZ", "510300.SH", "688981.SH");
        assertThat(scores(result)).containsExactly(75.0, 62.5, 50.0, 43.8, 18.8);
    }

    @Test
    @DisplayName("WI-011 显式评分：score=false 的条件只过滤，不参与排序")
    void explicitScoringFilterOnly() {
        ScreenerResult result = screenerService.screen(all(
                cs("return20", CompareOp.GT, 0, true, null, null),
                cs("volatility20", CompareOp.LTE, 6, false, null, null)), 1);

        assertThat(ids(result)).containsExactly(
                "002594.SZ", "300750.SZ", "000001.SZ", "510300.SH", "688981.SH");
        assertThat(scores(result)).containsExactly(100.0, 75.0, 50.0, 25.0, 0.0);
    }

    @Test
    @DisplayName("WI-011 显式评分：方向可覆盖运算符推断（lt 也可越大越好）")
    void explicitScoringOverridesDirection() {
        ScreenerResult result = screenerService.screen(all(
                cs("return20", CompareOp.LT, 20, true, null, ScoreDirection.HIGHER)), 1);

        assertThat(ids(result)).containsExactly(
                "000001.SZ", "510300.SH", "688981.SH", "600000.SH");
        assertThat(scores(result)).containsExactly(100.0, 66.7, 33.3, 0.0);
    }

    @Test
    @DisplayName("WI-011 显式评分：eq 条件必须显式方向，给方向后可正常评分")
    void explicitScoringEqRequiresDirection() {
        assertThatThrownBy(() -> screenerService.screen(all(
                cs("volatility20", CompareOp.EQ, 2, true, null, null)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("等值条件参与评分需指定方向");

        ScreenerResult result = screenerService.screen(all(
                cs("volatility20", CompareOp.EQ, 2, true, null, ScoreDirection.LOWER)), 1);
        assertThat(ids(result)).containsExactly("000001.SZ");
        assertThat(scores(result)).containsExactly(100.0);
    }

    @Test
    @DisplayName("WI-011 校验：布尔/排除组不可评分；权重范围；权重方向须随评分出现")
    void validationRejectsInvalidScoring() {
        assertThatThrownBy(() -> screenerService.screen(all(
                cs("aboveMa60", CompareOp.EQ, 1, true, null, null)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不支持参与评分");
        assertThatThrownBy(() -> screenerService.screen(new ScreenerDsl(List.of(), List.of(),
                List.of(cs("return20", CompareOp.GT, 0, true, null, null))), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("排除组条件不支持参与评分");
        assertThatThrownBy(() -> screenerService.screen(all(
                cs("return20", CompareOp.GT, 0, true, 0.05, null)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("权重必须在");
        assertThatThrownBy(() -> screenerService.screen(all(
                cs("return20", CompareOp.GT, 0, true, 11.0, null)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("权重必须在");
        assertThatThrownBy(() -> screenerService.screen(all(
                cs("return20", CompareOp.GT, 0, false, 2.0, null)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只有参与评分");
        assertThatThrownBy(() -> screenerService.screen(all(
                cs("return20", CompareOp.GT, 0, false, null, ScoreDirection.LOWER)), 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只有参与评分");
    }

    @Test
    @DisplayName("WI-014 区间评分：区间内满分，两侧容差独立线性衰减")
    void bandScoringDecaysBySideTolerance() {
        ScreenerResult result = screenerService.screen(all(
                cs("volatility20", CompareOp.LTE, 6, false, null, null),
                cb("volatility20", CompareOp.LTE, 6, 2, 4, 1, 2)), 1);

        assertThat(ids(result)).containsExactly(
                "000001.SZ", "300750.SZ", "600000.SH", "002594.SZ", "510300.SH", "688981.SH");
        assertThat(scores(result)).containsExactly(100.0, 100.0, 50.0, 0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("WI-014 区间评分：下限等于上限即理想值，越偏离得分越低")
    void bandScoringIdealValue() {
        ScreenerResult result = screenerService.screen(all(
                cb("volatility20", CompareOp.LTE, 6, 3, 3, 2, 4)), 1);

        assertThat(ids(result)).containsExactly(
                "300750.SZ", "000001.SZ", "600000.SH", "002594.SZ", "510300.SH", "688981.SH");
        assertThat(scores(result)).containsExactly(75.0, 50.0, 50.0, 25.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("WI-014 区间子分与百分位子分按权重混合")
    void bandScoringMixesWithPercentile() {
        ScreenerResult result = screenerService.screen(all(
                cs("return20", CompareOp.GT, 0, true, 3.0, ScoreDirection.HIGHER),
                cb("volatility20", CompareOp.LTE, 6, 2, 4, 1, 2)), 1);

        assertThat(ids(result)).containsExactly(
                "300750.SZ", "002594.SZ", "000001.SZ", "510300.SH", "688981.SH");
        assertThat(scores(result)).containsExactly(81.3, 75.0, 62.5, 18.8, 0.0);
    }

    @Test
    @DisplayName("WI-014 区间评分：eq 条件方向已显式，可直接用区间评分")
    void bandScoringAllowsEq() {
        ScreenerResult result = screenerService.screen(all(
                cb("volatility20", CompareOp.EQ, 2, 1, 3, 1, 1)), 1);

        assertThat(ids(result)).containsExactly("000001.SZ");
        assertThat(scores(result)).containsExactly(100.0);
    }

    @Test
    @DisplayName("WI-014 校验：区间参数缺失 / 越界 / 下限大于上限 / 容差非正 / 非区间方向携带")
    void validationRejectsInvalidBand() {
        assertThatThrownBy(() -> screenerService.screen(all(
                new Condition("volatility20", CompareOp.LTE, 6.0, true, 1.0,
                        ScoreDirection.BAND, 2.0, null, 1.0, 1.0)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("区间评分需要下限、上限与两侧容差");
        assertThatThrownBy(() -> screenerService.screen(all(
                cb("volatility20", CompareOp.LTE, 6, 4, 2, 1, 1)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("区间下限不能大于上限");
        assertThatThrownBy(() -> screenerService.screen(all(
                cb("volatility20", CompareOp.LTE, 6, 2, 4, 0, 1)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("区间容差必须大于 0");
        assertThatThrownBy(() -> screenerService.screen(all(
                new Condition("volatility20", CompareOp.LTE, 6.0, true, 1.0,
                        ScoreDirection.HIGHER, 2.0, 4.0, 1.0, 1.0)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非区间评分不能设置区间参数");
        assertThatThrownBy(() -> screenerService.screen(all(
                new Condition("volatility20", CompareOp.LTE, 6.0, false, null, null,
                        2.0, 4.0, 1.0, 1.0)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有参与评分的条件才能设置");
        assertThatThrownBy(() -> screenerService.screen(all(
                cb("aboveMa60", CompareOp.EQ, 1, 0, 1, 1, 1)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持参与评分");
    }

    @Test
    @DisplayName("WI-014 回看：区间评分与页面筛选共用同一排序口径")
    void bandScoringSharedWithLookBack() {
        LocalDate history = TRADE_DATE.minusDays(300);
        seedHistory(history);
        ScreenerDsl dsl = all(cb("return60", CompareOp.GT, -100, 0, 100, 50, 50));

        ScreenerResult page = screenerService.screenAt(dsl, history, 1);
        List<String> lookback = screenerService.evaluateAt(dsl, history, 10).stream()
                .map(ScreenerRow::instrumentId)
                .toList();

        assertThat(lookback).isEqualTo(ids(page));
        assertThat(lookback).containsExactly("000001.SZ", "600000.SH");
        assertThat(scores(page)).containsExactly(100.0, 80.0);
    }

    @Test
    @DisplayName("WI-014 解析：区间字段 JSON 往返一致，旧条件不输出区间字段")
    void parsesBandCondition() {
        ScreenerDsl dsl = all(cb("volumeRatio20", CompareOp.GTE, 1.2, 1.2, 2.0, 0.5, 0.4));
        assertThat(screenerService.parse(screenerService.toJson(dsl))).isEqualTo(dsl);
        assertThat(screenerService.toJson(dsl))
                .contains("\"direction\":\"band\"")
                .contains("\"bandLow\":1.2")
                .contains("\"bandHigh\":2.0")
                .contains("\"bandLowTolerance\":0.5")
                .contains("\"bandHighTolerance\":0.4");

        ScreenerDsl legacy = all(c("return20", CompareOp.GT, 0));
        assertThat(screenerService.toJson(legacy)).doesNotContain("band");
    }

    @Test
    @DisplayName("WI-011 解析：显式评分字段 JSON 往返一致；非法方向按格式错误拒绝")
    void parseExplicitScoringAndRejectsUnknownDirection() {
        ScreenerDsl dsl = all(cs("return20", CompareOp.GT, 0, true, 2.0, ScoreDirection.HIGHER));
        assertThat(screenerService.parse(screenerService.toJson(dsl))).isEqualTo(dsl);

        ScreenerDsl legacy = all(c("return20", CompareOp.GT, 0));
        String legacyJson = screenerService.toJson(legacy);
        assertThat(legacyJson)
                .doesNotContain("score").doesNotContain("weight").doesNotContain("direction");
        assertThat(screenerService.parse(legacyJson)).isEqualTo(legacy);

        assertThatThrownBy(() -> screenerService.parse(
                "{\"all\":[{\"factor\":\"return20\",\"op\":\"gt\",\"value\":0,"
                        + "\"score\":true,\"direction\":\"up\"}],\"any\":[],\"not\":[]}"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("条件树格式错误");
    }

    private Condition c(String factor, CompareOp op, double value) {
        return new Condition(factor, op, value);
    }

    private Condition cs(String factor, CompareOp op, double value, boolean score,
                         Double weight, ScoreDirection direction) {
        return new Condition(factor, op, value, score, weight, direction);
    }

    private Condition cb(String factor, CompareOp op, double value, double low, double high,
                         double toleranceLow, double toleranceHigh) {
        return new Condition(factor, op, value, true, 1.0, ScoreDirection.BAND,
                low, high, toleranceLow, toleranceHigh);
    }

    private ScreenerDsl all(Condition... conditions) {
        return new ScreenerDsl(List.of(conditions), List.of(), List.of());
    }

    private List<String> ids(ScreenerResult result) {
        return result.rows().stream().map(ScreenerRow::instrumentId).toList();
    }

    private List<Double> scores(ScreenerResult result) {
        return result.rows().stream().map(ScreenerRow::score).toList();
    }

    private Instrument instrument(String id, String board, String name, String type) {
        String[] parts = id.split("\\.");
        return new Instrument(id, type, parts[1], parts[0], name, board,
                "CNY", "Asia/Shanghai", LocalDate.of(2000, 1, 1), null, Instrument.STATUS_LISTED);
    }

    private MarketSnapshot snapshot(String id, Double pe, Double pb) {
        return new MarketSnapshot(id, 10.0, 0.0, 1_000L, 1.0e6, 0.01,
                pe, pb, 1.0e9, 0.8e9, "TEST", FETCHED_AT);
    }

    private DailyBar bar(String id, LocalDate date, double close) {
        return new DailyBar(id, date, close, close, close, close,
                null, 100_000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }

    /** 历史截面数据：仅 000001.SZ 当日上涨，600000.SH 当日无因子行（模拟停牌）。 */
    private void seedHistory(LocalDate date) {
        dailyBarMapper.upsertBatch(List.of(
                bar("000001.SZ", date, 8.0),
                bar("600000.SH", date, 7.0)));
        factorDailyMapper.upsertBatch(List.of(
                factor("000001.SZ", date, 10.0, 10.0, 0.20, 0.30, 0.010, -0.05),
                factor("600000.SH", date, 10.0, 10.0, -0.05, -0.10, 0.050, -0.50)));
    }

    private FactorDaily factor(String id, double ma20, double ma60,
                               double return20, double return60,
                               double volatility20, double pctFrom52wHigh) {
        return factor(id, TRADE_DATE, ma20, ma60, return20, return60, volatility20, pctFrom52wHigh);
    }

    private FactorDaily factor(String id, LocalDate date, double ma20, double ma60,
                               double return20, double return60,
                               double volatility20, double pctFrom52wHigh) {
        return new FactorDaily(id, date, 10.0, 10.0, ma20, ma60,
                0.0, 0.0, 0.0, 50.0, 50.0, 50.0,
                return20, return60, volatility20, pctFrom52wHigh,
                null, null, null, null, null, null, null, null, null);
    }
}
