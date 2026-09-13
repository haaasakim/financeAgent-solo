package org.haaasaki.financeagent.indicator;

/**
 * MACD 计算结果。
 *
 * <p>单位与口径见 {@link Macd}。
 *
 * @param dif  快慢 EMA 之差，元；与输入等长
 * @param dea  DIF 的 EMA（信号线），元；与输入等长
 * @param hist 柱值 {@code dif - dea}，元；<b>不乘 2</b>
 */
public record MacdResult(double[] dif, double[] dea, double[] hist) {
}
