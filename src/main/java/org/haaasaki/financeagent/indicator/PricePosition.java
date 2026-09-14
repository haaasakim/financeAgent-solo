package org.haaasaki.financeagent.indicator;

/**
 * 距离区间高点的位置（如「距 52 周高点」）。
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟。滚动窗口最大值用单调队列实现（O(n)），
 * 避免长窗口下的 O(n×window) 扫描。回看不足 {@code window} 根时为 null。
 */
public final class PricePosition {

    private PricePosition() {
    }

    /**
     * {@code close[t] / max(close[t-window+1 .. t]) - 1}。
     *
     * @param closes 前复权收盘价序列，按时间升序，单位元
     * @param window 回看根数（250 ≈ 52 周），必须为正
     * @return 与输入等长，小数且 ≤ 0；回看不足或区间最大值为 0 时为 null
     */
    public static Double[] pctFromHigh(double[] closes, int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window 必须为正: " + window);
        }
        Double[] result = new Double[closes.length];
        if (closes.length < window) {
            return result;
        }

        // 单调递减队列保存候选最大值下标
        int[] candidates = new int[closes.length];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < closes.length; i++) {
            while (tail > head && closes[candidates[tail - 1]] <= closes[i]) {
                tail--;
            }
            candidates[tail++] = i;
            if (candidates[head] <= i - window) {
                head++;
            }
            if (i >= window - 1) {
                double highest = closes[candidates[head]];
                result[i] = highest == 0.0 ? null : closes[i] / highest - 1.0;
            }
        }
        return result;
    }

    /**
     * {@code close[t] / min(close[t-window+1 .. t]) - 1}。
     *
     * @param closes 前复权收盘价序列，按时间升序，单位元
     * @param window 回看根数（250 ≈ 52 周），必须为正
     * @return 与输入等长，小数且 ≥ 0；回看不足或区间最小值为 0 时为 null
     */
    public static Double[] pctFromLow(double[] closes, int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window 必须为正: " + window);
        }
        Double[] result = new Double[closes.length];
        if (closes.length < window) {
            return result;
        }

        // 单调递增队列保存候选最小值下标
        int[] candidates = new int[closes.length];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < closes.length; i++) {
            while (tail > head && closes[candidates[tail - 1]] >= closes[i]) {
                tail--;
            }
            candidates[tail++] = i;
            if (candidates[head] <= i - window) {
                head++;
            }
            if (i >= window - 1) {
                double lowest = closes[candidates[head]];
                result[i] = lowest == 0.0 ? null : closes[i] / lowest - 1.0;
            }
        }
        return result;
    }

    /**
     * 是否创窗口新高：{@code close[t] >= max(close[t-window+1 .. t])} → 1，否则 0。
     *
     * @param closes 前复权收盘价序列，按时间升序，单位元
     * @param window 窗口根数（含当日），必须为正
     * @return 与输入等长，1 或 0；回看不足时为 null（缺失不视为「否」）
     */
    public static Double[] newHigh(double[] closes, int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window 必须为正: " + window);
        }
        Double[] result = new Double[closes.length];
        if (closes.length < window) {
            return result;
        }

        int[] candidates = new int[closes.length];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < closes.length; i++) {
            while (tail > head && closes[candidates[tail - 1]] <= closes[i]) {
                tail--;
            }
            candidates[tail++] = i;
            if (candidates[head] <= i - window) {
                head++;
            }
            if (i >= window - 1) {
                result[i] = closes[candidates[head]] <= closes[i] ? 1.0 : 0.0;
            }
        }
        return result;
    }
}
