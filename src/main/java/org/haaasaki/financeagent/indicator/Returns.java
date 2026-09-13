package org.haaasaki.financeagent.indicator;

/**
 * N 日收益。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。前 {@code period} 个位置因回看不足为 null。
 */
public final class Returns {

    private Returns() {
    }

    /**
     * 简单收益：{@code close[t] / close[t-period] - 1}。
     *
     * @param closes 前复权收盘价序列，按时间升序，单位元
     * @param period 回看交易日数，必须为正
     * @return 与输入等长，小数（0.125 = 12.5%）；基准价为 0 或回看不足时为 null
     */
    public static Double[] simple(double[] closes, int period) {
        if (period <= 0) {
            throw new IllegalArgumentException("period 必须为正: " + period);
        }
        Double[] result = new Double[closes.length];
        for (int i = period; i < closes.length; i++) {
            double base = closes[i - period];
            result[i] = base == 0.0 ? null : closes[i] / base - 1.0;
        }
        return result;
    }
}
