package org.haaasaki.financeagent.backtest.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.haaasaki.financeagent.data.support.BoardClassifier;

/**
 * 涨跌停幅度与一字板判定（回测撮合用）。
 *
 * <p>幅度按「板块 × 风险警示」取值：主板风险警示（ST）±5%，主板 ±10%，
 * 创业板 / 科创板 ±20%，北交所 ±30%，ETF ±10%。
 * 判定依据是当日 {@code open == high == low == close} 且开盘价触及按幅度算出的
 * 涨跌停价；不单凭涨跌幅判定（见 docs/DATA_CONTRACT.md 第 6 节）。
 */
public final class BoardLimit {

    private BoardLimit() {
    }

    public static double ratio(String board, boolean riskWarned) {
        if (BoardClassifier.ETF.equals(board)) {
            return 0.10;
        }
        if (riskWarned) {
            return switch (board) {
                case BoardClassifier.GEM, BoardClassifier.STAR -> 0.20;
                case BoardClassifier.BSE -> 0.30;
                default -> 0.05;
            };
        }
        return switch (board) {
            case BoardClassifier.GEM, BoardClassifier.STAR -> 0.20;
            case BoardClassifier.BSE -> 0.30;
            default -> 0.10;
        };
    }

    public static double limitUp(double preClose, double ratio) {
        return round2(preClose * (1 + ratio));
    }

    public static double limitDown(double preClose, double ratio) {
        return round2(preClose * (1 - ratio));
    }

    /** 价格是否触及涨跌停价（2 位小数取整后的容差比较）。 */
    public static boolean atLimit(double price, double limit) {
        return Math.abs(price - limit) <= 0.0051;
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
