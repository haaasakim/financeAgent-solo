package org.haaasaki.financeagent.indicator;

/**
 * 真实波幅比率（ATR Ratio）：ATR / 收盘价。
 *
 * <p>口径：真实波幅 {@code TR = max(high-low, |high-pre_close|, |low-pre_close|)}，
 * {@code pre_close} 缺失（或 ≤0）时退化为 {@code high-low}；
 * ATR 为最近 N 根 bar 的 TR <b>简单均值</b>（非 Wilder 平滑），再除以当日收盘价。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。回看不足或收盘价 ≤0 时为 null。
 */
public final class Atr {

    private Atr() {
    }

    /**
     * @param highs     最高价序列，按时间升序，单位元
     * @param lows      最低价序列，按时间升序，单位元
     * @param closes    收盘价序列，按时间升序，单位元
     * @param preCloses 前收盘价序列，与输入等长；缺失为 null
     * @param period    窗口根数，必须 ≥ 2
     * @return 与输入等长，ATR/收盘，小数（0.03 = 3%）；回看不足或收盘价 ≤0 时为 null
     */
    public static Double[] ratio(double[] highs, double[] lows, double[] closes,
                                 Double[] preCloses, int period) {
        if (period < 2) {
            throw new IllegalArgumentException("period 必须 ≥ 2: " + period);
        }
        Double[] result = new Double[closes.length];
        for (int i = period - 1; i < closes.length; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += trueRange(highs[j], lows[j], preCloses[j]);
            }
            double close = closes[i];
            result[i] = close > 0.0 ? sum / period / close : null;
        }
        return result;
    }

    private static double trueRange(double high, double low, Double preClose) {
        double range = high - low;
        if (preClose != null && preClose > 0.0) {
            range = Math.max(range, Math.abs(high - preClose));
            range = Math.max(range, Math.abs(low - preClose));
        }
        return range;
    }
}
