package org.haaasaki.financeagent.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.BacktestTaskMapper;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.StrategyMapper;
import org.haaasaki.financeagent.data.mapper.ValuationDailyMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.ValuationDaily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 回测服务校验测试：策略可回测性、区间与基准校验，失败不创建任务。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:backtestservicetest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class BacktestServiceTest {

    private static final String STRATEGY_OK = "{\"all\":[{\"factor\":\"return20\",\"op\":\"gte\",\"value\":0}],"
            + "\"any\":[],\"not\":[]}";
    private static final String STRATEGY_SNAPSHOT = "{\"all\":[{\"factor\":\"peTtm\",\"op\":\"lte\",\"value\":30}],"
            + "\"any\":[],\"not\":[]}";

    @Autowired
    private BacktestService backtestService;
    @Autowired
    private StrategyMapper strategyMapper;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private BacktestTaskMapper backtestTaskMapper;
    @Autowired
    private ValuationDailyMapper valuationDailyMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM backtest_daily");
        jdbcTemplate.update("DELETE FROM backtest_trade");
        jdbcTemplate.update("DELETE FROM backtest_task");
        jdbcTemplate.update("DELETE FROM valuation_daily");
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM instrument");
        jdbcTemplate.update("DELETE FROM strategy");

        instrumentMapper.upsertBatch(List.of(new Instrument("000300.SH", Instrument.TYPE_INDEX,
                "SH", "000300", "沪深300", "INDEX", "CNY", "Asia/Shanghai",
                LocalDate.of(2005, 1, 1), null, Instrument.STATUS_LISTED)));
        dailyBarMapper.upsertBatch(List.of(new DailyBar("000300.SH", LocalDate.of(2026, 9, 11),
                4000, 4010, 3990, 4005, 3995.0, 1_000_000L, null, null, null,
                DailyBar.ADJ_QFQ, "TEST")));
        strategyMapper.upsert("技术策略", null, STRATEGY_OK);
        strategyMapper.upsert("估值策略", null, STRATEGY_SNAPSHOT);
    }

    @Test
    @DisplayName("估值历史未回填：含 PE/PB 的策略拒绝并提示先回填")
    void rejectsValuationStrategyWithoutHistory() {
        long id = strategyMapper.findAll().stream()
                .filter(strategy -> "估值策略".equals(strategy.name()))
                .findFirst().orElseThrow().id();

        assertThatThrownBy(() -> backtestService.createTask(request(id, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 9, 11))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("估值历史尚未回填");
        assertThat(backtestTaskMapper.findRecent(10)).isEmpty();
    }

    @Test
    @DisplayName("开始日期早于估值数据覆盖：拒绝并提示最早日期")
    void rejectsStartBeforeValuationCoverage() {
        long id = strategyMapper.findAll().stream()
                .filter(strategy -> "估值策略".equals(strategy.name()))
                .findFirst().orElseThrow().id();
        valuationDailyMapper.upsertBatch(List.of(new ValuationDaily("000300.SH",
                LocalDate.of(2017, 1, 3), 12.0, 1.5, "TEST")));

        assertThatThrownBy(() -> backtestService.createTask(request(id, LocalDate.of(2016, 12, 1),
                LocalDate.of(2026, 9, 11))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("估值数据自 2017-01-03 起");
        assertThat(backtestTaskMapper.findRecent(10)).isEmpty();
    }

    @Test
    @DisplayName("结束日期晚于最新数据日期：拒绝并提示")
    void rejectsEndDateAfterDataThrough() {
        long id = technicalStrategyId();

        assertThatThrownBy(() -> backtestService.createTask(request(id, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 10, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最新数据日期");
        assertThat(backtestTaskMapper.findRecent(10)).isEmpty();
    }

    @Test
    @DisplayName("策略不存在：拒绝")
    void rejectsUnknownStrategy() {
        assertThatThrownBy(() -> backtestService.createTask(request(9999, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 9, 11))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("策略不存在");
    }

    private long technicalStrategyId() {
        return strategyMapper.findAll().stream()
                .filter(strategy -> "技术策略".equals(strategy.name()))
                .findFirst().orElseThrow().id();
    }

    private BacktestRequest request(long strategyId, LocalDate start, LocalDate end) {
        return new BacktestRequest(strategyId, start, end, "MONTHLY", 20,
                new BigDecimal("1000000"), new BigDecimal("0.03"), new BigDecimal("0.05"),
                new BigDecimal("5"), "000300.SH");
    }
}
