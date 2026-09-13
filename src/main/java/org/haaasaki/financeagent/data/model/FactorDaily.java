package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 日频技术因子（{@code factor_daily} 表）。
 *
 * <p>由 {@code daily_bar} 的前复权收盘价派生，按标的自身 bar 序列计算（不对齐日历，
 * 停牌缺口自然跳过）。缺失语义：回看不足的列为 null，<b>不填 0</b>。
 * 指标公式与参数见 docs/work-items/WI-003-指标计算与因子表.md 第 2.2 节。
 *
 * @param instrumentId    全局唯一标识
 * @param tradeDate       交易日
 * @param ma5 / ma10 / ma20 / ma60 收盘简单均线，元
 * @param macdDif / macdDea / macdHist MACD(12,26,9)，元；{@code hist = dif - dea}（不乘 2）
 * @param rsi6 / rsi12 / rsi24 Wilder RSI，0~100
 * @param return20 / return60 N 日简单收益，小数（0.125 = 12.5%）
 * @param volatility20    最近 20 个日收益率样本标准差，小数，未年化
 * @param pctFrom52wHigh  {@code close / 近 250 根 bar 最高收盘 - 1}，小数，≤ 0
 */
public record FactorDaily(
        String instrumentId,
        LocalDate tradeDate,
        Double ma5,
        Double ma10,
        Double ma20,
        Double ma60,
        Double macdDif,
        Double macdDea,
        Double macdHist,
        Double rsi6,
        Double rsi12,
        Double rsi24,
        Double return20,
        Double return60,
        Double volatility20,
        Double pctFrom52wHigh) {
}
