package org.haaasaki.financeagent.indicator;

/**
 * MACD（Moving Average Convergence Divergence）。
 *
 * <p>参数固定为 (12, 26, 9)：{@code EMA12}、{@code EMA26} 均以首根收盘价播种
 * （{@code EMA[0] = close[0]}），{@code DIF = EMA12 - EMA26}，{@code DEA = EMA(DIF, 9)} 同法播种，
 * {@code HIST = DIF - DEA}。首根即有值——早期数值不稳定但确定，契约见 WI-003。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟；缺失用 null 表达，但 MACD 无回看不足语义。
 */
public final class Macd {

    public static final int FAST_PERIOD = 12;
    public static final int SLOW_PERIOD = 26;
    public static final int SIGNAL_PERIOD = 9;

    private Macd() {
    }

    /**
     * @param closes 前复权收盘价序列，按时间升序，单位元
     * @return DIF / DEA / HIST 三列，长度与输入一致；空输入返回三个空数组
     */
    public static MacdResult calculate(double[] closes) {
        double[] fast = ema(closes, FAST_PERIOD);
        double[] slow = ema(closes, SLOW_PERIOD);
        double[] dif = new double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            dif[i] = fast[i] - slow[i];
        }
        double[] dea = ema(dif, SIGNAL_PERIOD);
        double[] hist = new double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            hist[i] = dif[i] - dea[i];
        }
        return new MacdResult(dif, dea, hist);
    }

    /** 指数移动平均，以首值播种：{@code EMA[t] = EMA[t-1] + α * (value[t] - EMA[t-1])}。 */
    private static double[] ema(double[] values, int period) {
        double[] result = new double[values.length];
        if (values.length == 0) {
            return result;
        }
        double alpha = 2.0 / (period + 1);
        result[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            result[i] = result[i - 1] + alpha * (values[i] - result[i - 1]);
        }
        return result;
    }
}
