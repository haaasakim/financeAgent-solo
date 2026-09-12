package org.haaasaki.financeagent.data.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * SQLite 的 {@link LocalDate} 类型处理器。
 *
 * <p>SQLite 没有原生日期类型，本项目按契约把日期存为 ISO 文本 {@code YYYY-MM-DD}
 * （见 docs/DATA_CONTRACT.md 第 4 节：交易日为无时区日期）。
 * sqlite-jdbc 的 {@code getObject(col, LocalDate.class)} 支持不完整，
 * 因此显式按字符串读写，不依赖驱动的隐式转换。
 *
 * <p>{@code created_at} / {@code updated_at} 这类时间戳由 SQL 侧生成
 * （{@code strftime}），Java 侧不写入，因此本项目只需要 LocalDate 一个处理器。
 *
 * <p>通过 {@code mybatis.type-handlers-package} 全局注册，映射处无需逐个指定。
 */
@MappedTypes(LocalDate.class)
public class LocalDateTypeHandler extends BaseTypeHandler<LocalDate> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDate parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.toString());
    }

    @Override
    public LocalDate getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public LocalDate getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public LocalDate getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private LocalDate parse(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text);
    }
}
