package org.haaasaki.financeagent.backtest;

/**
 * 回测记录排序字段白名单。
 *
 * <p>请求里的排序键只会映射到本枚举，不接受用户原始字符串；
 * URL 键为 camelCase，SQL 列为 snake_case（见 {@link #column()}）。
 * 指标列可能为 NULL（排队 / 运行中 / 失败任务），SQL 排序时空值恒在最后。
 */
public enum BacktestTaskSort {

    ID("id", "id", "编号", false),
    CUMULATIVE_RETURN("cumulativeReturn", "cumulative_return", "累计收益", true),
    ANNUAL_RETURN("annualReturn", "annual_return", "年化收益", true),
    MAX_DRAWDOWN("maxDrawdown", "max_drawdown", "最大回撤", true),
    SHARPE("sharpe", "sharpe", "夏普", true);

    /** 默认按编号降序（最新在前），与旧「最近回测」展示一致。 */
    public static final BacktestTaskSort DEFAULT = ID;

    private final String key;
    private final String column;
    private final String label;
    private final boolean numeric;

    BacktestTaskSort(String key, String column, String label, boolean numeric) {
        this.key = key;
        this.column = column;
        this.label = label;
        this.numeric = numeric;
    }

    /** URL 参数值。 */
    public String key() {
        return key;
    }

    /** 数据库列名（白名单，禁止使用请求原文拼接）。 */
    public String column() {
        return column;
    }

    /** 表头展示名。 */
    public String label() {
        return label;
    }

    /** 数值列右对齐。 */
    public boolean numeric() {
        return numeric;
    }

    /** 未知键回退到默认（编号降序），保证链接可手工修改而不报错。 */
    public static BacktestTaskSort fromKey(String key) {
        if (key != null) {
            for (BacktestTaskSort field : values()) {
                if (field.key.equalsIgnoreCase(key)) {
                    return field;
                }
            }
        }
        return DEFAULT;
    }
}
