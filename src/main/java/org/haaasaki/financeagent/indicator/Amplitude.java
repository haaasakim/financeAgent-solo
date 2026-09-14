package org.haaasaki.financeagent.indicator;

/**
 * 平均振幅：{@code mean((high - low) / pre_close)}。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。仅取窗口内 {@code pre_close} 非空且 &gt; 0 的 bar
 * （首根 bar 无前收盘价，自然跳过）；回看不足或窗口内无有效 bar 时为 null。
 */
public final class Amplitude {

    private Amplitude() {
    }

    /**
     * @param highs     最高价序列，按时间升序，单位元
     * @param lows      最低价序列，按时间升序，单位元
     * @param preCloses 前收盘价序列，与输入等长；缺失为 null
     * @param window    窗口根数，必须为正
     * @return 与输入等长，小数（0.05 = 5%）；回看不足或无有效 bar 时为 null
     */
    public static Double[] mean(double[] highs, double[] lows, Double[] preCloses, int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window 必须为正: " + window);
        }
        Double[] result = new Double[highs.length];
        for (int i = window - 1; i < highs.length; i++) {
            double sum = 0;
            int count = 0;
            for (int j = i - window + 1; j <= i; j++) {
                Double preClose = preCloses[j];
                if (preClose == null || preClose <= 0.0) {
                    continue;
                }
                sum += (highs[j] - lows[j]) / preClose;
                count++;
            }
            result[i] = count == 0 ? null : sum / count;
        }
        return result;
    }
}
