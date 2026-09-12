package org.haaasaki.financeagent.market;

import java.util.List;

/**
 * ECharts K 线数据。
 *
 * <p>均线由浏览器端按收盘价计算，服务端只下发前复权 OHLC 与成交量，
 * 避免 M2 提前引入指标层（指标计算属 M3）。
 *
 * @param dates   交易日，格式 {@code YYYY-MM-DD}
 * @param candles 蜡烛数据，每项为 {@code [open, close, low, high]}（ECharts candlestick 顺序）
 * @param volumes 成交量，股，与 {@code dates} 一一对应
 */
public record KlineChartData(
        List<String> dates,
        List<List<Double>> candles,
        List<Long> volumes) {
}
