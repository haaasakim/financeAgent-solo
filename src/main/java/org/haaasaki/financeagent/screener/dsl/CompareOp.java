package org.haaasaki.financeagent.screener.dsl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 比较运算符白名单。
 *
 * <p>DSL 中存 {@link #key()}（如 {@code gte}），SQL 使用 {@link #sql()}；
 * 用户输入中的运算符只会映射到本枚举，绝不允许原始字符串进入 SQL。
 */
public enum CompareOp {

    GT("gt", ">", ">"),
    GTE("gte", ">=", "≥"),
    LT("lt", "<", "<"),
    LTE("lte", "<=", "≤"),
    EQ("eq", "=", "=");

    private final String key;
    private final String sql;
    private final String label;

    CompareOp(String key, String sql, String label) {
        this.key = key;
        this.sql = sql;
        this.label = label;
    }

    @JsonValue
    public String key() {
        return key;
    }

    /** 仅由白名单提供，禁止拼接用户字符串。 */
    public String sql() {
        return sql;
    }

    public String label() {
        return label;
    }

    @JsonCreator
    public static CompareOp fromKey(String key) {
        if (key != null) {
            for (CompareOp op : values()) {
                if (op.key.equalsIgnoreCase(key)) {
                    return op;
                }
            }
        }
        return null;
    }
}
