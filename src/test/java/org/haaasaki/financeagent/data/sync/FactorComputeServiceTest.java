package org.haaasaki.financeagent.data.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 因子计算任务测试：真实 SQLite 临时库验证回填、幂等与增量窗口语义。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:factortest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class FactorComputeServiceTest {

    private static final String ID = "000001.SZ";

    @Autowired
    private FactorComputeService factorComputeService;
    @Autowired
    private InstrumentMapper instrumentMapper;
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
        jdbcTemplate.update("DELETE FROM instrument");
        jdbcTemplate.update("DELETE FROM sync_log");
        instrumentMapper.upsertBatch(List.of(instrument(ID)));
    }

    @Test
    @DisplayName("全量回填：行数与日K一致，MA5 与手算一致，重复执行幂等")
    void initializeBackfillsAllBars() {
        List<DailyBar> bars = bars(ID, 300, 0);
        dailyBarMapper.upsertBatch(bars);

        SyncResult first = factorComputeService.initializeFactors();

        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(first.success()).isEqualTo(1);
        assertThat(factorDailyMapper.countByInstrument(ID)).isEqualTo(300);

        // 最后 5 根收盘 = 39.5 / 39.6 / 39.7 / 39.8 / 39.9，均值 39.7
        String lastDate = bars.get(299).tradeDate().toString();
        Double ma5 = queryDouble("SELECT ma5 FROM factor_daily WHERE instrument_id = ? AND trade_date = ?",
                lastDate);
        assertThat(ma5).isCloseTo(39.7, within(1e-9));

        // 单调上涨序列最后一天即 250 日最高，距高点为 0
        Double pct = queryDouble(
                "SELECT pct_from_52w_high FROM factor_daily WHERE instrument_id = ? AND trade_date = ?",
                lastDate);
        assertThat(pct).isCloseTo(0.0, within(1e-12));

        factorComputeService.initializeFactors();
        assertThat(factorDailyMapper.countByInstrument(ID)).isEqualTo(300);
    }

    @Test
    @DisplayName("增量重算：只覆盖最近窗口，窗口外历史保持不变，新日期有因子")
    void updateOnlyRewritesRecentWindow() {
        List<DailyBar> bars = bars(ID, 300, 0);
        dailyBarMapper.upsertBatch(bars);
        factorComputeService.initializeFactors();

        // 索引 20 在最近 260 根之外（总长 300，尾部窗口从索引 40 起）
        String probeDate = bars.get(20).tradeDate().toString();
        Double historyBefore = queryDouble(
                "SELECT ma5 FROM factor_daily WHERE instrument_id = ? AND trade_date = ?", probeDate);
        assertThat(historyBefore).isNotNull();

        List<DailyBar> more = bars(ID, 5, 300);
        dailyBarMapper.upsertBatch(more);

        SyncResult result = factorComputeService.updateFactors();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(factorDailyMapper.countByInstrument(ID)).isEqualTo(305);
        Double historyAfter = queryDouble(
                "SELECT ma5 FROM factor_daily WHERE instrument_id = ? AND trade_date = ?", probeDate);
        assertThat(historyAfter).isEqualTo(historyBefore);

        String newDate = more.get(4).tradeDate().toString();
        Double newMa5 = queryDouble(
                "SELECT ma5 FROM factor_daily WHERE instrument_id = ? AND trade_date = ?", newDate);
        assertThat(newMa5).isNotNull();

        // 重复执行不产生重复行
        factorComputeService.updateFactors();
        assertThat(factorDailyMapper.countByInstrument(ID)).isEqualTo(305);
    }

    @Test
    @DisplayName("增量遇到从未算过的标的：自动全量回填")
    void updateBackfillsInstrumentsWithoutFactors() {
        String newId = "600000.SH";
        instrumentMapper.upsertBatch(List.of(instrument(newId)));
        dailyBarMapper.upsertBatch(bars(newId, 30, 0));

        SyncResult result = factorComputeService.updateFactors();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(factorDailyMapper.countByInstrument(newId)).isEqualTo(30);
    }

    private Double queryDouble(String sql, String tradeDate) {
        List<Double> values = jdbcTemplate.queryForList(sql, Double.class, ID, tradeDate);
        return values.isEmpty() ? null : values.get(0);
    }

    private Instrument instrument(String id) {
        String[] parts = id.split("\\.");
        return new Instrument(id, Instrument.TYPE_STOCK, parts[1], parts[0], "测试标的",
                "MAIN", "CNY", "Asia/Shanghai", null, null, Instrument.STATUS_LISTED);
    }

    /** 生成 {@code count} 根连续日期的日K，收盘价从 {@code 10 + startIndex * 0.1} 起每根 +0.1。 */
    private List<DailyBar> bars(String instrumentId, int count, int startIndex) {
        List<DailyBar> result = new ArrayList<>(count);
        LocalDate date = LocalDate.of(2023, 1, 1).plusDays(startIndex);
        for (int i = 0; i < count; i++) {
            double close = 10 + (startIndex + i) * 0.1;
            result.add(new DailyBar(instrumentId, date, close, close, close, close,
                    null, 1_000L, null, null, null, DailyBar.ADJ_QFQ, "TEST"));
            date = date.plusDays(1);
        }
        return result;
    }
}
