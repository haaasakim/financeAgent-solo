package org.haaasaki.financeagent.indicator;

/**
 * RSI（Relative Strength Index，Wilder 平滑）。
 *
 * <p>口径：首个平均涨跌为前 N 个交易日涨跌的算术均值，之后用 Wilder 平滑
 * {@code avg = (avg * (N-1) + x) / N}；{@code RSI = 100 - 100 / (1 + avgGain / avgLoss)}。
 *
 * <p>边界（契约级）：{@code avgLoss = 0 且 avgGain > 0} → 100；{@code avgGain = 0 且 avgLoss > 0} → 0；
 * 两者同为 0（横盘）→ 50。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。
 */
public final class Rsi {

    /** 判定均值是否为零的容差：价格步进可能产生 1e-16 级浮点残差。 */
    private static final double ZERO_TOLERANCE = 1e-12;

    private Rsi() {
    }

    /**
     * @param closes 前复权收盘价序列，按时间升序，单位元
     * @param period 周期（如 6 / 12 / 24），必须为正
     * @return 与输入等长，取值 0~100；前 {@code period} 个位置因回看不足为 null
     */
    public static Double[] rsi(double[] closes, int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period 必须为正: " + period);
        }
        Double[] result = new Double[closes.length];
        if (closes.length < period + 1) {
            return result;
        }

        double gainSum = 0;
        double lossSum = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) {
                gainSum += change;
            } else {
                lossSum -= change;
            }
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        result[period] = value(avgGain, avgLoss);

        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            result[i] = value(avgGain, avgLoss);
        }
        return result;
    }

    private static double value(double avgGain, double avgLoss) {
        if (avgLoss <= ZERO_TOLERANCE && avgGain <= ZERO_TOLERANCE) {
            return 50.0;
        }
        if (avgLoss <= ZERO_TOLERANCE) {
            return 100.0;
        }
        if (avgGain <= ZERO_TOLERANCE) {
            return 0.0;
        }
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss);
    }
}
