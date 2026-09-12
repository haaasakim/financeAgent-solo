package org.haaasaki.financeagent.market;

/**
 * 市场列表排序字段白名单。
 *
 * <p>用户请求中的排序键只会映射到本枚举，SQL 中的列名来自 {@link #column()}，
 * 不接受任何用户原始字符串（见 docs/BACKEND_GUIDE.md 第 4 节）。
 */
public enum MarketSortField {

    CODE("code", "i.code", "代码", false),
    NAME("name", "i.name", "名称", false),
    LAST_PRICE("lastPrice", "ms.last_price", "最新价", true),
    PCT_CHG("pctChg", "ms.pct_chg", "涨跌幅", true),
    AMOUNT("amount", "ms.amount", "成交额", true),
    TURNOVER_RATE("turnoverRate", "ms.turnover_rate", "换手率", true),
    PE_TTM("peTtm", "ms.pe_ttm", "PE-TTM", true),
    TOTAL_MV("totalMv", "ms.total_mv", "总市值", true);

    private final String key;
    private final String column;
    private final String label;
    private final boolean numeric;

    MarketSortField(String key, String column, String label, boolean numeric) {
        this.key = key;
        this.column = column;
        this.label = label;
        this.numeric = numeric;
    }

    /** URL 参数值。 */
    public String key() {
        return key;
    }

    /** SQL 排序列（含表别名），仅由本枚举提供。 */
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

    /** 未知键回退到代码升序，保证链接可手工修改而不报错。 */
    public static MarketSortField fromKey(String key) {
        if (key != null) {
            for (MarketSortField field : values()) {
                if (field.key.equalsIgnoreCase(key)) {
                    return field;
                }
            }
        }
        return CODE;
    }
}
