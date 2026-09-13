package org.haaasaki.financeagent.backtest.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.ValuationDailyMapper;
import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTrade;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.ValuationDaily;
import org.haaasaki.financeagent.screener.dsl.CompareOp;
import org.haaasaki.financeagent.screener.dsl.Condition;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 回测引擎测试：固定数据快照下验证撮合、费用、涨跌停、停牌与复现性。
 *
 * <p>契约（docs/work-items/WI-006-回测模块.md）：信号 T 日收盘、成交 T+1 开盘；
 * 一字涨跌停不可成交、停牌延后、100 股整手、费用与滑点必须进成交明细。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:backtestengine?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class BacktestEngineTest {

    private static final String A = "000001.SZ";
    private static final String B = "600000.SH";
    private static final String INDEX = "000300.SH";
    private static final LocalDate START = LocalDate.of(2026, 8, 24);
    private static final LocalDate END = LocalDate.of(2026, 9, 11);
    private static final LocalDate PRE_RANGE = LocalDate.of(2026, 8, 3);

    @Autowired
    private BacktestEngine backtestEngine;
    @Autowired
    private InstrumentMapper instrumentMapper;
    @Autowired
    private DailyBarMapper dailyBarMapper;
    @Autowired
    private FactorDailyMapper factorDailyMapper;
    @Autowired
    private ValuationDailyMapper valuationDailyMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<LocalDate> tradingDays;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM backtest_daily");
        jdbcTemplate.update("DELETE FROM backtest_trade");
        jdbcTemplate.update("DELETE FROM backtest_task");
        jdbcTemplate.update("DELETE FROM valuation_daily");
        jdbcTemplate.update("DELETE FROM factor_daily");
        jdbcTemplate.update("DELETE FROM daily_bar");
        jdbcTemplate.update("DELETE FROM trading_calendar");
        jdbcTemplate.update("DELETE FROM instrument");

        instrumentMapper.upsertBatch(List.of(
                instrument(A, "MAIN", "平安银行", Instrument.TYPE_STOCK),
                instrument(B, "MAIN", "浦发银行", Instrument.TYPE_STOCK),
                instrument(INDEX, "INDEX", "沪深300", Instrument.TYPE_INDEX)));

        tradingDays = new ArrayList<>();
        LocalDate day = START;
        while (!day.isAfter(END)) {
            if (day.getDayOfWeek().getValue() <= 5) {
                tradingDays.add(day);
            }
            day = day.plusDays(1);
        }

        List<DailyBar> bars = new ArrayList<>();
        double benchmarkClose = 4000;
        LocalDate previous = null;
        for (LocalDate current : tradingDays) {
            bars.add(bar(INDEX, current, benchmarkClose, benchmarkClose + 5, null));
            benchmarkClose += 5;
            previous = current;
        }
        // 上市不足 5 个交易日的排除口径：A/B 在区间开始前已有行情
        bars.add(bar(A, PRE_RANGE, 9.5, 9.5, null));
        bars.add(bar(B, PRE_RANGE, 8.0, 8.0, null));
        for (LocalDate current : tradingDays) {
            bars.add(bar(A, current, 10.00, 10.20, previous == null ? 10.00 : 10.10));
            bars.add(bar(B, current, 8.00, 8.20, 8.00));
        }
        dailyBarMapper.upsertBatch(bars);
    }

    @Test
    @DisplayName("信号 T 收盘、成交 T+1 开盘：费用与 100 股整手进成交明细，两次运行结果一致")
    void buysTopScoreAtNextOpenWithCostsAndIsReproducible() {
        factorDailyMapper.upsertBatch(List.of(
                factor(A, LocalDate.of(2026, 8, 31), 0.10),
                factor(B, LocalDate.of(2026, 8, 31), 0.05),
                // 信号日之后的因子更强，但不得影响 T 日选股（无未来函数）
                factor(A, LocalDate.of(2026, 9, 1), 0.01),
                factor(B, LocalDate.of(2026, 9, 1), 0.30)));

        BacktestResult first = backtestEngine.run(1, params(RebalanceFrequency.MONTHLY, 1,
                new BigDecimal("100000")), dsl(), null);
        BacktestResult second = backtestEngine.run(1, params(RebalanceFrequency.MONTHLY, 1,
                new BigDecimal("100000")), dsl(), null);

        assertThat(second.daily()).isEqualTo(first.daily());
        assertThat(second.trades()).isEqualTo(first.trades());

        assertThat(first.trades()).hasSize(1);
        BacktestTrade buy = first.trades().get(0);
        assertThat(buy.instrumentId()).isEqualTo(A);
        assertThat(buy.side()).isEqualTo(BacktestTrade.SIDE_BUY);
        assertThat(buy.status()).isEqualTo(BacktestTrade.STATUS_FILLED);
        assertThat(buy.signalDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(buy.filledDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(buy.price()).isEqualTo(10.005);
        assertThat(buy.shares()).isEqualTo(9900L);
        assertThat(buy.amount()).isEqualTo(99_049.50);
        assertThat(buy.fee()).isEqualTo(29.71);

        LocalDate execution = LocalDate.of(2026, 9, 1);
        BacktestDaily executionDay = first.daily().stream()
                .filter(row -> row.tradeDate().equals(execution))
                .findFirst().orElseThrow();
        assertThat(executionDay.nav()).isEqualTo(101_900.79);
    }

    @Test
    @DisplayName("每日调仓：每个交易日收盘都是信号日，次日开盘即调仓")
    void dailyRebalanceSignalsEveryTradingDay() {
        factorDailyMapper.upsertBatch(List.of(factor(A, LocalDate.of(2026, 8, 24), 0.10)));

        BacktestResult result = backtestEngine.run(1, params(RebalanceFrequency.DAILY, 1,
                new BigDecimal("100000")), dsl(), null);

        // 08-24 信号买入 → 08-25 开盘成交；08-25 截面无因子行 → 目标为空，当日信号卖出 → 08-26 成交
        assertThat(result.trades()).hasSize(2);
        BacktestTrade buy = result.trades().get(0);
        assertThat(buy.side()).isEqualTo(BacktestTrade.SIDE_BUY);
        assertThat(buy.status()).isEqualTo(BacktestTrade.STATUS_FILLED);
        assertThat(buy.signalDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(buy.filledDate()).isEqualTo(LocalDate.of(2026, 8, 25));

        BacktestTrade sell = result.trades().get(1);
        assertThat(sell.side()).isEqualTo(BacktestTrade.SIDE_SELL);
        assertThat(sell.status()).isEqualTo(BacktestTrade.STATUS_FILLED);
        assertThat(sell.signalDate()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(sell.filledDate()).isEqualTo(LocalDate.of(2026, 8, 26));
    }

    @Test
    @DisplayName("一字涨停不可买入：订单延后到下一交易日成交")
    void defersBuyOnOneWordLimitUp() {
        LocalDate execution = LocalDate.of(2026, 9, 1);
        // 09-01 一字涨停：preClose 10.10 → 11.11
        DailyBar limitUp = bar(A, execution, 11.11, 11.11, 10.10);
        jdbcTemplate.update("DELETE FROM daily_bar WHERE instrument_id = ? AND trade_date = ?",
                A, execution.toString());
        dailyBarMapper.upsertBatch(List.of(limitUp, bar(A, execution.plusDays(1), 11.00, 11.50, 11.11)));
        factorDailyMapper.upsertBatch(List.of(factor(A, LocalDate.of(2026, 8, 31), 0.10)));

        BacktestResult result = backtestEngine.run(1, params(RebalanceFrequency.MONTHLY, 1,
                new BigDecimal("100000")), dsl(), null);

        assertThat(result.trades()).hasSize(1);
        BacktestTrade buy = result.trades().get(0);
        assertThat(buy.status()).isEqualTo(BacktestTrade.STATUS_FILLED);
        assertThat(buy.filledDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(buy.price()).isEqualTo(11.006);
    }

    @Test
    @DisplayName("停牌无法卖出：订单整笔延后，期末如实记为未成交")
    void expiringSellWhenSuspendedUntilEnd() {
        factorDailyMapper.upsertBatch(List.of(
                factor(A, LocalDate.of(2026, 8, 28), 0.10),
                factor(B, LocalDate.of(2026, 8, 28), 0.05),
                factor(A, LocalDate.of(2026, 9, 4), 0.10),
                factor(B, LocalDate.of(2026, 9, 4), -0.10)));
        // B 从 09-07 起停牌（无记录）
        jdbcTemplate.update("DELETE FROM daily_bar WHERE instrument_id = ? AND trade_date > ?",
                B, "2026-09-04");

        BacktestResult result = backtestEngine.run(1, params(RebalanceFrequency.WEEKLY, 2,
                new BigDecimal("200000")), dsl(), null);

        BacktestTrade buyB = result.trades().stream()
                .filter(t -> t.instrumentId().equals(B) && t.side().equals(BacktestTrade.SIDE_BUY))
                .findFirst().orElseThrow();
        assertThat(buyB.status()).isEqualTo(BacktestTrade.STATUS_FILLED);
        assertThat(buyB.filledDate()).isEqualTo(LocalDate.of(2026, 8, 31));

        BacktestTrade sellB = result.trades().stream()
                .filter(t -> t.instrumentId().equals(B) && t.side().equals(BacktestTrade.SIDE_SELL))
                .findFirst().orElseThrow();
        assertThat(sellB.status()).isEqualTo(BacktestTrade.STATUS_EXPIRED);
        assertThat(sellB.filledDate()).isNull();
        assertThat(sellB.reason()).isEqualTo(BacktestTrade.REASON_SUSPENDED);
    }

    @Test
    @DisplayName("现金不足 100 股：订单未成交并记录原因，净值不变")
    void expiresBuyWhenCashBelowOneLot() {
        factorDailyMapper.upsertBatch(List.of(factor(A, LocalDate.of(2026, 8, 31), 0.10)));

        BacktestResult result = backtestEngine.run(1, params(RebalanceFrequency.MONTHLY, 1,
                new BigDecimal("1000")), dsl(), null);

        assertThat(result.trades()).hasSize(1);
        BacktestTrade buy = result.trades().get(0);
        assertThat(buy.status()).isEqualTo(BacktestTrade.STATUS_EXPIRED);
        assertThat(buy.reason()).isEqualTo(BacktestTrade.REASON_CASH);
        assertThat(result.daily()).allSatisfy(row ->
                assertThat(row.nav()).isEqualTo(1000.00));
    }

    @Test
    @DisplayName("估值条件回测：信号日取当日 PE 历史值，信号日之后的变化不得影响选股")
    void backtestsValuationConditionAtSignalDate() {
        factorDailyMapper.upsertBatch(List.of(
                factor(A, LocalDate.of(2026, 8, 31), 0.10),
                factor(B, LocalDate.of(2026, 8, 31), 0.05)));
        valuationDailyMapper.upsertBatch(List.of(
                valuation(A, LocalDate.of(2026, 8, 31), 5.0),
                valuation(B, LocalDate.of(2026, 8, 31), 40.0),
                // 信号日之后 B 的估值降到 4，若被使用即为未来函数
                valuation(B, LocalDate.of(2026, 9, 1), 4.0)));
        ScreenerDsl valuationDsl = new ScreenerDsl(
                List.of(new Condition("peTtm", CompareOp.LTE, 30.0)), List.of(), List.of());

        BacktestResult result = backtestEngine.run(1,
                params(RebalanceFrequency.MONTHLY, 1, new BigDecimal("100000")),
                valuationDsl, null);

        assertThat(result.trades()).hasSize(1);
        BacktestTrade buy = result.trades().get(0);
        assertThat(buy.instrumentId()).isEqualTo(A);
        assertThat(buy.status()).isEqualTo(BacktestTrade.STATUS_FILLED);
        assertThat(buy.filledDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    private BacktestParams params(RebalanceFrequency frequency, int topN, BigDecimal capital) {
        return new BacktestParams(1, "测试策略", "hash", START, END, frequency, topN, capital,
                new BigDecimal("0.0003"), new BigDecimal("5.00"),
                new BigDecimal("0.0005"), new BigDecimal("0.0005"),
                INDEX, END);
    }

    private ScreenerDsl dsl() {
        return new ScreenerDsl(
                List.of(new Condition("return20", CompareOp.GTE, 0.0)), List.of(), List.of());
    }

    private Instrument instrument(String id, String board, String name, String type) {
        String[] parts = id.split("\\.");
        return new Instrument(id, type, parts[1], parts[0], name, board,
                "CNY", "Asia/Shanghai", LocalDate.of(2000, 1, 1), null, Instrument.STATUS_LISTED);
    }

    private DailyBar bar(String id, LocalDate date, double open, double close, Double preClose) {
        return new DailyBar(id, date, open, open, open, close, preClose,
                100_000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }

    private FactorDaily factor(String id, LocalDate date, double return20) {
        return new FactorDaily(id, date, 10.0, 10.0, 10.0, 10.0,
                0.0, 0.0, 0.0, 50.0, 50.0, 50.0,
                return20, return20, 0.02, -0.10);
    }

    private ValuationDaily valuation(String id, LocalDate date, double peTtm) {
        return new ValuationDaily(id, date, peTtm, 1.0, "TEST");
    }
}
