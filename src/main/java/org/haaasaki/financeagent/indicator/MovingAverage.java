package org.haaasaki.financeagent.indicator;

/**
 * 简单移动平均（SMA）。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。入参为按时间升序的价格序列。
 */
public final class MovingAverage {

    private MovingAverage() {
    }

    /**
     * 计算简单移动平均。
     *
     * @param values 价格序列，按时间升序
     * @param window 窗口长度（交易日数），必须为正
     * @return 与输入等长的数组；前 {@code window - 1} 个位置因回看不足为 null
     */
    public static Double[] sma(double[] values, int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window 必须为正: " + window);
        }
        Double[] result = new Double[values.length];
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= window) {
                sum -= values[i - window];
            }
            result[i] = i >= window - 1 ? sum / window : null;
        }
        return result;
    }
}
