package org.haaasaki.financeagent.data.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 迁移器测试：验证契约表建全、版本被记录、重复执行幂等。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:migratortest?mode=memory&cache=shared",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
class DatabaseMigratorTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("迁移建出契约规定的全部表")
    void createsContractTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table'", String.class);

        assertThat(tables).contains(
                "instrument", "daily_bar", "trading_calendar", "sync_log", "task",
                "market_snapshot", "factor_daily", "strategy",
                "valuation_daily", "schema_version");
    }

    @Test
    @DisplayName("迁移版本被记录到 schema_version，且只记录一次")
    void recordsVersionsOnce() {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT version FROM schema_version", Integer.class);

        assertThat(versions).containsExactly(1, 2, 3, 4, 6);
    }

    @Test
    @DisplayName("重复执行迁移不报错、不重复记录")
    void isIdempotent() throws Exception {
        new DatabaseMigrator(jdbcTemplate).run(null);

        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT version FROM schema_version", Integer.class);
        assertThat(versions).containsExactly(1, 2, 3, 4, 6);
    }

    @Test
    @DisplayName("daily_bar 主键为 (instrument_id, trade_date)，重复写入不产生重复行")
    void dailyBarPrimaryKeyIsComposite() {
        jdbcTemplate.update("""
                INSERT INTO daily_bar (instrument_id, trade_date, open, high, low, close, volume,
                                       adj_type, source, fetched_at, created_at, updated_at)
                VALUES ('600000.SH', '2024-09-02', 7.54, 7.67, 7.52, 7.56, 42178600,
                        'QFQ', 'TEST', '2024-09-02T15:00', '2024-09-02T15:00', '2024-09-02T15:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO daily_bar (instrument_id, trade_date, open, high, low, close, volume,
                                       adj_type, source, fetched_at, created_at, updated_at)
                VALUES ('600000.SH', '2024-09-02', 7.54, 7.67, 7.52, 7.56, 42178600,
                        'QFQ', 'TEST', '2024-09-02T15:00', '2024-09-02T15:00', '2024-09-02T15:00')
                ON CONFLICT (instrument_id, trade_date) DO UPDATE SET close = excluded.close
                """);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_bar WHERE instrument_id = '600000.SH'", Long.class);
        assertThat(count).isEqualTo(1L);
    }
}
