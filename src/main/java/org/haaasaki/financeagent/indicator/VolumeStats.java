package org.haaasaki.financeagent.indicator;

/**
 * 成交量派生指标：量比与量能变化。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。成交量单位为股，按标的自身 bar 序列滚动
 * （不对齐日历，停牌缺口自然跳过）；回看不足或分母为 0 时为 null。
 */
public final class VolumeStats {

    private VolumeStats() {
    }

    /**
     * 量比：{@code vol[t] / mean(vol[t-lookback .. t-1])}，分母不含当日。
     *
     * @param volumes  成交量序列，按时间升序，单位股
     * @param lookback 分母回看根数，必须为正
     * @return 与输入等长，倍；回看不足或分母为 0 时为 null
     */
    public static Double[] ratio(long[] volumes, int lookback) {
        if (lookback <= 0) {
            throw new IllegalArgumentException("lookback 必须为正: " + lookback);
        }
        Double[] result = new Double[volumes.length];
        for (int i = lookback; i < volumes.length; i++) {
            double sum = 0;
            for (int j = i - lookback; j < i; j++) {
                sum += volumes[j];
            }
            result[i] = sum == 0.0 ? null : volumes[i] / (sum / lookback);
        }
        return result;
    }

    /**
     * 量能变化：{@code mean(vol[t-short+1 .. t]) / mean(vol[t-long+1 .. t]) - 1}，两窗口均含当日。
     *
     * @param volumes     成交量序列，按时间升序，单位股
     * @param shortWindow 短窗口根数（如 5）
     * @param longWindow  长窗口根数（如 20），必须 ≥ 短窗口
     * @return 与输入等长，小数（0.5 = 放量 50%）；回看不足或分母为 0 时为 null
     */
    public static Double[] change(long[] volumes, int shortWindow, int longWindow) {
        if (shortWindow <= 0 || longWindow < shortWindow) {
            throw new IllegalArgumentException(
                    "窗口非法: short=" + shortWindow + ", long=" + longWindow);
        }
        Double[] result = new Double[volumes.length];
        for (int i = longWindow - 1; i < volumes.length; i++) {
            double shortSum = 0;
            for (int j = i - shortWindow + 1; j <= i; j++) {
                shortSum += volumes[j];
            }
            double longSum = 0;
            for (int j = i - longWindow + 1; j <= i; j++) {
                longSum += volumes[j];
            }
            double longMean = longSum / longWindow;
            result[i] = longMean == 0.0
                    ? null
                    : (shortSum / shortWindow) / longMean - 1.0;
        }
        return result;
    }
}
