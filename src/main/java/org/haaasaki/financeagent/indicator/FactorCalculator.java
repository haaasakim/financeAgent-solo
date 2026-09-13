package org.haaasaki.financeagent.indicator;

import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.FactorDaily;

/**
 * 因子组装器：把一只标的的日K序列转换成 {@link FactorDaily} 行。
 *
 * <p>契约参数（WI-003 冻结）：
 * <ul>
 *   <li>均线窗口：5 / 10 / 20 / 60</li>
 *   <li>MACD：(12, 26, 9)，HIST = DIF - DEA</li>
 *   <li>RSI：6 / 12 / 24</li>
 *   <li>收益：20 / 60 交易日</li>
 *   <li>波动率：20 个日收益率，样本标准差，未年化</li>
 *   <li>距 52 周高点：近 250 根 bar 最高收盘</li>
 * </ul>
 *
 * <p>纯函数：无 I/O、无状态、不依赖时钟；一次遍历组装全部列，避免重复求值。
 */
public final class FactorCalculator {

    public static final int[] MA_WINDOWS = {5, 10, 20, 60};
    public static final int[] RSI_PERIODS = {6, 12, 24};
    public static final int RETURN_SHORT = 20;
    public static final int RETURN_LONG = 60;
    public static final int VOLATILITY_PERIOD = 20;
    /** 52 周约 250 个交易日。 */
    public static final int HIGH_WINDOW = 250;

    private FactorCalculator() {
    }

    /**
     * @param bars 单只标的的日K，按交易日升序，前复权
     * @return 与输入等长的因子行；空输入返回空列表（不返回 null）
     */
    public static List<FactorDaily> calculate(List<DailyBar> bars) {
        if (bars.isEmpty()) {
            return List.of();
        }

        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).close();
        }

        Double[] ma5 = MovingAverage.sma(closes, MA_WINDOWS[0]);
        Double[] ma10 = MovingAverage.sma(closes, MA_WINDOWS[1]);
        Double[] ma20 = MovingAverage.sma(closes, MA_WINDOWS[2]);
        Double[] ma60 = MovingAverage.sma(closes, MA_WINDOWS[3]);
        MacdResult macd = Macd.calculate(closes);
        Double[] rsi6 = Rsi.rsi(closes, RSI_PERIODS[0]);
        Double[] rsi12 = Rsi.rsi(closes, RSI_PERIODS[1]);
        Double[] rsi24 = Rsi.rsi(closes, RSI_PERIODS[2]);
        Double[] return20 = Returns.simple(closes, RETURN_SHORT);
        Double[] return60 = Returns.simple(closes, RETURN_LONG);
        Double[] volatility20 = Volatility.ofReturns(closes, VOLATILITY_PERIOD);
        Double[] pctFromHigh = PricePosition.pctFromHigh(closes, HIGH_WINDOW);

        List<FactorDaily> rows = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            DailyBar bar = bars.get(i);
            rows.add(new FactorDaily(
                    bar.instrumentId(),
                    bar.tradeDate(),
                    ma5[i],
                    ma10[i],
                    ma20[i],
                    ma60[i],
                    macd.dif()[i],
                    macd.dea()[i],
                    macd.hist()[i],
                    rsi6[i],
                    rsi12[i],
                    rsi24[i],
                    return20[i],
                    return60[i],
                    volatility20[i],
                    pctFromHigh[i]));
        }
        return rows;
    }
}
