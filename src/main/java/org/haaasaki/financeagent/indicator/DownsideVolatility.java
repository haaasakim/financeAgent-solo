package org.haaasaki.financeagent.indicator;

/**
 * 下行波动率：只取负收益的样本标准差。
 *
 * <p>口径：最近 N 个日简单收益率 {@code close[i]/close[i-1]-1} 中 {@code < 0} 的子集，
 * 计算<b>样本</b>标准差（分母 n-1），单位小数、未年化。
 * 窗口内负收益少于 2 个时不可估，返回 null（缺失保持缺失，不填 0）。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。
 */
public final class DownsideVolatility {

    private DownsideVolatility() {
    }

    /**
     * @param closes 前复权收盘价序列，按时间升序，单位元
     * @param period 收益率个数（如 20），必须 ≥ 2
     * @return 与输入等长；不足 {@code period + 1} 根收盘价或负收益少于 2 个时为 null
     */
    public static Double[] ofReturns(double[] closes, int period) {
        if (period < 2) {
            throw new IllegalArgumentException("period 必须 ≥ 2: " + period);
        }
        Double[] result = new Double[closes.length];
        if (closes.length < period + 1) {
            return result;
        }

        double[] returns = new double[closes.length - 1];
        for (int i = 1; i < closes.length; i++) {
            returns[i - 1] = closes[i] / closes[i - 1] - 1.0;
        }

        for (int i = period; i < closes.length; i++) {
            // 窗口为 returns[i-period .. i-1]
            double sum = 0;
            int count = 0;
            for (int j = i - period; j < i; j++) {
                if (returns[j] < 0.0) {
                    sum += returns[j];
                    count++;
                }
            }
            if (count < 2) {
                continue;
            }
            double mean = sum / count;
            double sumSquares = 0;
            for (int j = i - period; j < i; j++) {
                if (returns[j] < 0.0) {
                    double diff = returns[j] - mean;
                    sumSquares += diff * diff;
                }
            }
            result[i] = Math.sqrt(sumSquares / (count - 1));
        }
        return result;
    }
}
