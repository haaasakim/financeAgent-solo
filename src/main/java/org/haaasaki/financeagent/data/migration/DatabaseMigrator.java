package org.haaasaki.financeagent.data.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 轻量数据库迁移器。
 *
 * <p>行为：读取 {@code classpath:db/migration/V<版本>__<描述>.sql}，按版本号升序执行，
 * 已执行过的版本跳过，执行记录写入 {@code schema_version} 表。
 *
 * <p>不使用 Flyway 的原因见 pom.xml 中的说明（社区版无 SQLite 支持，
 * 其 sqlite 模块许可与版本均不确定）。
 *
 * <p>约定：迁移脚本中的 DDL 必须自带 {@code IF NOT EXISTS}，
 * 这样即使某次执行中途失败，下次启动重跑也不会因对象已存在而中断。
 */
@Component
@Order(1)
public class DatabaseMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    private static final String SCRIPT_LOCATION = "classpath:db/migration/*.sql";
    private static final Pattern SCRIPT_NAME = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        ensureVersionTable();

        List<Script> scripts = loadScripts();
        Set<Integer> applied = appliedVersions();
        int executed = 0;
        for (Script script : scripts) {
            if (applied.contains(script.version())) {
                continue;
            }
            apply(script);
            executed++;
        }

        if (executed == 0) {
            log.info("数据库结构已是最新（共 {} 个迁移脚本）", scripts.size());
        } else {
            log.info("数据库迁移完成：本次执行 {} 个脚本，累计 {} 个", executed, scripts.size());
        }
    }

    private void apply(Script script) {
        log.info("执行迁移 V{} - {}", script.version(), script.description());
        for (String statement : splitStatements(script.content())) {
            jdbcTemplate.execute(statement);
        }
        jdbcTemplate.update(
                "INSERT INTO schema_version (version, description, script, applied_at) VALUES (?, ?, ?, ?)",
                script.version(), script.description(), script.name(), LocalDateTime.now().toString());
    }

    private void ensureVersionTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version     INTEGER PRIMARY KEY,
                    description TEXT,
                    script      TEXT,
                    applied_at  TEXT NOT NULL
                )
                """);
    }

    private Set<Integer> appliedVersions() {
        return new HashSet<>(jdbcTemplate.queryForList("SELECT version FROM schema_version", Integer.class));
    }

    private List<Script> loadScripts() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SCRIPT_LOCATION);
        List<Script> scripts = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            Matcher matcher = SCRIPT_NAME.matcher(filename);
            if (!matcher.matches()) {
                log.warn("跳过不符合 V<版本>__<描述>.sql 命名的脚本：{}", filename);
                continue;
            }
            scripts.add(new Script(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2),
                    filename,
                    resource.getContentAsString(StandardCharsets.UTF_8)));
        }
        scripts.sort(Comparator.comparingInt(Script::version));
        return scripts;
    }

    /**
     * 按语句拆分脚本：跳过空行与 {@code --} 注释行，以分号结尾视为一条语句。
     *
     * <p>本项目迁移脚本中的 SQL 不含包含分号的字符串字面量，因此该拆分策略足够。
     * 若未来引入此类语句，需要替换为真正的 SQL 解析器。
     */
    private List<String> splitStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private record Script(int version, String description, String name, String content) {
    }
}
