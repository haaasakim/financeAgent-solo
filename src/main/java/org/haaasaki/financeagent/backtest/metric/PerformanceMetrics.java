package org.haaasaki.financeagent.backtest.metric;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTrade;

/**
 * 绩效指标计算（纯函数）。口径见 docs/work-items/WI-006-回测模块.md 第 2.3 节。
 *
 * <p>夏普用日收益样本标准差（n-1）× sqrt(252)，无风险利率取 0；
 * 胜率按已成交卖出单的净额盈亏统计；换手按买入 + 卖出成交额的一半对平均净值年化。
 */
public final class PerformanceMetrics {

    private static final double TRADING_DAYS_PER_YEAR = 252.0;

    private PerformanceMetrics() {
    }

    public static BacktestMetrics compute(List<BacktestDaily> daily,
                                          BigDecimal initialCapital,
                                          List<BacktestTrade> trades,
                                          LocalDate start,
                                          LocalDate end) {
        if (daily.isEmpty()) {
            return new BacktestMetrics(initialCapital.doubleValue(),
                    null, null, null, null, null, null);
        }

        double initial = initialCapital.doubleValue();
        double finalNav = daily.get(daily.size() - 1).nav();
        double cumulative = finalNav / initial - 1;

        long calendarDays = ChronoUnit.DAYS.between(start, end);
        Double annual = calendarDays > 0 && cumulative > -1
                ? Math.pow(1 + cumulative, 365.0 / calendarDays) - 1
                : null;

        Double maxDrawdown = daily.stream()
                .mapToDouble(BacktestDaily::drawdown)
                .min()
                .orElse(0);

        Double sharpe = sharpe(daily);
        Double winRate = winRate(trades);
        Double turnover = turnover(daily, trades, calendarDays);

        return new BacktestMetrics(finalNav, cumulative, annual, maxDrawdown, sharpe, winRate, turnover);
    }

    private static Double sharpe(List<BacktestDaily> daily) {
        if (daily.size() < 2) {
            return null;
        }
        double[] returns = new double[daily.size() - 1];
        for (int i = 1; i < daily.size(); i++) {
            double previous = daily.get(i - 1).nav();
            returns[i - 1] = previous == 0 ? 0 : daily.get(i).nav() / previous - 1;
        }
        double mean = 0;
        for (double r : returns) {
            mean += r;
        }
        mean /= returns.length;
        double variance = 0;
        for (double r : returns) {
            variance += (r - mean) * (r - mean);
        }
        variance /= returns.length - 1;
        double std = Math.sqrt(variance);
        if (std < 1e-12) {
            return null;
        }
        return mean / std * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    private static Double winRate(List<BacktestTrade> trades) {
        long closed = 0;
        long wins = 0;
        for (BacktestTrade trade : trades) {
            if (!BacktestTrade.SIDE_SELL.equals(trade.side())
                    || !BacktestTrade.STATUS_FILLED.equals(trade.status())
                    || trade.pnl() == null) {
                continue;
            }
            closed++;
            if (trade.pnl() > 0) {
                wins++;
            }
        }
        return closed == 0 ? null : (double) wins / closed;
    }

    private static Double turnover(List<BacktestDaily> daily,
                                   List<BacktestTrade> trades,
                                   long calendarDays) {
        if (calendarDays <= 0) {
            return null;
        }
        double amount = 0;
        for (BacktestTrade trade : trades) {
            if (BacktestTrade.STATUS_FILLED.equals(trade.status()) && trade.amount() != null) {
                amount += trade.amount();
            }
        }
        double averageNav = daily.stream().mapToDouble(BacktestDaily::nav).average().orElse(0);
        if (averageNav <= 0) {
            return null;
        }
        double years = calendarDays / 365.0;
        return amount / 2 / averageNav / years;
    }
}
