package org.haaasaki.financeagent.data.support;

import org.haaasaki.financeagent.data.model.Instrument;

/**
 * 板块判定。
 *
 * <p>板块决定涨跌停幅度（见 docs/DATA_CONTRACT.md 第 6 节），
 * 回测撮合必须按板块取值，因此板块要在标的入库时就确定下来。
 *
 * <table>
 *   <caption>代码前缀与板块</caption>
 *   <tr><td>300 / 301</td><td>创业板（±20%）</td></tr>
 *   <tr><td>688 / 689</td><td>科创板（±20%）</td></tr>
 *   <tr><td>8xx / 920 / 43x</td><td>北交所（±30%）</td></tr>
 *   <tr><td>其余</td><td>沪深主板（±10%）</td></tr>
 * </table>
 */
public final class BoardClassifier {

    public static final String MAIN = "MAIN";
    public static final String GEM = "GEM";
    public static final String STAR = "STAR";
    public static final String BSE = "BSE";
    public static final String ETF = "ETF";
    public static final String INDEX = "INDEX";

    private BoardClassifier() {
    }

    /**
     * @param code           裸代码，如 {@code 600000}
     * @param instrumentType 标的类型，见 {@link Instrument}
     */
    public static String classify(String code, String instrumentType) {
        if (Instrument.TYPE_ETF.equals(instrumentType)) {
            return ETF;
        }
        if (Instrument.TYPE_INDEX.equals(instrumentType)) {
            return INDEX;
        }
        if (code == null || code.isBlank()) {
            return MAIN;
        }
        if (code.startsWith("300") || code.startsWith("301")) {
            return GEM;
        }
        if (code.startsWith("688") || code.startsWith("689")) {
            return STAR;
        }
        if (code.startsWith("8") || code.startsWith("43") || code.startsWith("920")) {
            return BSE;
        }
        return MAIN;
    }
}
