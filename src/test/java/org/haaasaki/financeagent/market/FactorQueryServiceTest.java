package org.haaasaki.financeagent.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 因子查询服务测试：验证倒序、条数归一化与未知标的处理。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:factorquerytest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class FactorQueryServiceTest {

    private static final String ID = "000001.SZ";

    @Autowired
    private FactorQueryService factorQueryService;
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
                factor("2026-09-09", 10.0),
                factor("2026-09-10", 11.0),
                factor("2026-09-11", 12.0)));
    }

    @Test
    @DisplayName("按交易日倒序返回，最新在前")
    void returnsLatestFirst() {
        FactorView view = factorQueryService.findRecent(ID, 60);

        assertThat(view.instrument()).isNotNull();
        assertThat(view.instrument().name()).isEqualTo("平安银行");
        assertThat(view.rows()).extracting(FactorDaily::tradeDate)
                .containsExactly(
                        LocalDate.of(2026, 9, 11),
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 9, 9));
    }

    @Test
    @DisplayName("limit 生效并归一化：0 取默认值，超上限截断")
    void normalizesLimit() {
        assertThat(FactorQueryService.normalizeLimit(0)).isEqualTo(FactorQueryService.DEFAULT_LIMIT);
        assertThat(FactorQueryService.normalizeLimit(-5)).isEqualTo(FactorQueryService.DEFAULT_LIMIT);
        assertThat(FactorQueryService.normalizeLimit(1000)).isEqualTo(FactorQueryService.MAX_LIMIT);
        assertThat(FactorQueryService.normalizeLimit(2)).isEqualTo(2);

        FactorView view = factorQueryService.findRecent(ID, 2);
        assertThat(view.rows()).hasSize(2);
        assertThat(view.rows().get(0).tradeDate()).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    @DisplayName("未知标的：档案为 null，行为空")
    void unknownInstrument() {
        FactorView view = factorQueryService.findRecent("999999.SH", 60);

        assertThat(view.instrument()).isNull();
        assertThat(view.rows()).isEmpty();
    }

    private FactorDaily factor(String date, double close) {
        return new FactorDaily(ID, LocalDate.parse(date),
                close, close, close, close,
                close, close, 0.0,
                50.0, 50.0, 50.0,
                0.1, 0.2, 0.01, -0.05, null, null, null, null, null, null, null, null, null);
    }
}
