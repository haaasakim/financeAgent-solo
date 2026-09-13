package org.haaasaki.financeagent.backtest.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.stream.Collectors;

import org.haaasaki.financeagent.backtest.TradingCalendarService;
import org.haaasaki.financeagent.backtest.cost.TradingCost;
import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTrade;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.InstrumentFirstBar;
import org.haaasaki.financeagent.data.model.ScreenerRow;
import org.haaasaki.financeagent.screener.ScreenerService;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.springframework.stereotype.Component;

/**
 * 回测引擎（单策略、日线、日度盯市）。
 *
 * <p>时序契约：信号日 T 收盘用 T 日及之前的数据求值（{@code ScreenerService.evaluateAt}），
 * 成交在 T+1 开盘；停牌与一字涨跌停不能成交，订单延后或作废并如实落库。
 * 完整口径见 docs/work-items/WI-006-回测模块.md。
 */
@Component
public class BacktestEngine {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int LOT_SIZE = 100;
    private static final int SEASONED_BARS = 5;

    private final ScreenerService screenerService;
    private final DailyBarMapper dailyBarMapper;
    private final InstrumentMapper instrumentMapper;
    private final TradingCalendarService tradingCalendarService;

    public BacktestEngine(ScreenerService screenerService,
                          DailyBarMapper dailyBarMapper,
                          InstrumentMapper instrumentMapper,
                          TradingCalendarService tradingCalendarService) {
        this.screenerService = screenerService;
        this.dailyBarMapper = dailyBarMapper;
        this.instrumentMapper = instrumentMapper;
        this.tradingCalendarService = tradingCalendarService;
    }

    /**
     * 执行回测。引擎不写库，返回日度序列与订单流水由服务层落库。
     *
     * @param taskId   任务 id，写入输出记录
     * @param params   参数（含复现依据）
     * @param dsl      策略条件树
     * @param progress 进度回调，可为 null
     */
    public BacktestResult run(long taskId,
                              BacktestParams params,
                              ScreenerDsl dsl,
                              BacktestProgress progress) {
        LocalDate start = params.startDate();
        LocalDate end = params.endDate();
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BusinessException("回测区间不合法：开始日期必须早于结束日期");
        }

        tradingCalendarService.ensureFromBenchmark(params.benchmarkId());
        List<LocalDate> days = tradingCalendarService.openDates(start, end);
        if (days.isEmpty()) {
            throw new BusinessException("区间内没有交易日，请检查区间与交易日历");
        }

        PriceBook priceBook = new PriceBook(dailyBarMapper, start, end);
        NavigableMap<LocalDate, Double> benchmarkCloses = priceBook.loadCloses(params.benchmarkId());
        if (benchmarkCloses.isEmpty()) {
            throw new BusinessException("基准在区间内没有日K数据");
        }
        BigDecimal initialCapital = params.initialCapital();
        double benchmarkFirst = benchmarkCloses.firstEntry().getValue();

        Map<String, Instrument> instruments = instrumentMapper.findWithDailyBars().stream()
                .collect(Collectors.toMap(Instrument::instrumentId, instrument -> instrument));
        Map<String, LocalDate> firstBars = dailyBarMapper.findFirstTradeDates().stream()
                .collect(Collectors.toMap(InstrumentFirstBar::instrumentId, InstrumentFirstBar::firstDate));
        Map<LocalDate, Integer> dayIndex = new HashMap<>();
        for (int i = 0; i < days.size(); i++) {
            dayIndex.put(days.get(i), i);
        }

        TradingCost cost = new TradingCost(params.commissionRate(), params.minCommission(),
                params.stampTaxRate(), params.slippage());
        Set<LocalDate> signalDates = signalDates(days, params.frequency());

        Portfolio portfolio = new Portfolio(initialCapital);
        List<Order> orders = new ArrayList<>();
        List<Order> pending = new ArrayList<>();
        List<BacktestDaily> daily = new ArrayList<>(days.size());
        BigDecimal peak = initialCapital;
        long orderSeq = 0;

        if (progress != null) {
            progress.total(days.size());
        }

        for (int i = 0; i < days.size(); i++) {
            LocalDate day = days.get(i);

            executePending(pending, portfolio, priceBook, instruments, cost, day, days, i);

            if (signalDates.contains(day) && i + 1 < days.size()) {
                LocalDate executionDay = days.get(i + 1);
                List<ScreenerRow> targets = targets(dsl, day, params.topN(),
                        firstBars, dayIndex, instruments);
                orderSeq = rebalance(pending, orders, portfolio, targets, day, executionDay, orderSeq);
            }

            BigDecimal marketValue = marketValue(portfolio, priceBook, day);
            BigDecimal nav = portfolio.cash().add(marketValue).setScale(2, RoundingMode.HALF_UP);
            peak = nav.max(peak);
            double drawdown = peak.signum() == 0
                    ? 0
                    : nav.subtract(peak).divide(peak, 10, RoundingMode.HALF_UP).doubleValue();
            Double benchmarkClose = benchmarkCloses.floorEntry(day) == null
                    ? benchmarkFirst
                    : benchmarkCloses.floorEntry(day).getValue();
            BigDecimal benchmarkNav = initialCapital
                    .multiply(BigDecimal.valueOf(benchmarkClose))
                    .divide(BigDecimal.valueOf(benchmarkFirst), 2, RoundingMode.HALF_UP);
            daily.add(new BacktestDaily(taskId, day, nav.doubleValue(), portfolio.cash().doubleValue(),
                    marketValue.doubleValue(), benchmarkNav.doubleValue(), drawdown));

            if (progress != null) {
                progress.done(i + 1);
            }
        }

        for (Order order : pending) {
            order.expire(order.reason() == null ? BacktestTrade.REASON_END : order.reason());
        }

        List<BacktestTrade> trades = orders.stream().map(order -> order.toTrade(taskId)).toList();
        return new BacktestResult(daily, trades);
    }

    /** 信号求值：评分前 N，剔除上市不足 5 个交易日与无档案的标的。 */
    private List<ScreenerRow> targets(ScreenerDsl dsl, LocalDate signal, int topN,
                                      Map<String, LocalDate> firstBars,
                                      Map<LocalDate, Integer> dayIndex,
                                      Map<String, Instrument> instruments) {
        List<ScreenerRow> ranked = screenerService.evaluateAt(dsl, signal, Integer.MAX_VALUE);
        Integer signalIndex = dayIndex.get(signal);
        List<ScreenerRow> targets = new ArrayList<>(topN);
        for (ScreenerRow row : ranked) {
            if (!instruments.containsKey(row.instrumentId()) || !seasoned(row.instrumentId(),
                    firstBars, dayIndex, signalIndex)) {
                continue;
            }
            targets.add(row);
            if (targets.size() == topN) {
                break;
            }
        }
        return targets;
    }

    private boolean seasoned(String instrumentId, Map<String, LocalDate> firstBars,
                             Map<LocalDate, Integer> dayIndex, Integer signalIndex) {
        LocalDate first = firstBars.get(instrumentId);
        Integer firstIndex = first == null ? null : dayIndex.get(first);
        if (firstIndex == null || signalIndex == null) {
            return true;
        }
        return firstIndex + SEASONED_BARS - 1 <= signalIndex;
    }

    private void executePending(List<Order> pending, Portfolio portfolio,
                                PriceBook priceBook, Map<String, Instrument> instruments,
                                TradingCost cost, LocalDate day, List<LocalDate> days, int dayIndex) {
        for (Order order : List.copyOf(pending)) {
            if (BacktestTrade.SIDE_SELL.equals(order.side()) && !order.orderDate().isAfter(day)) {
                executeSell(order, pending, portfolio, priceBook, instruments, cost, day, days, dayIndex);
            }
        }
        for (Order order : List.copyOf(pending)) {
            if (BacktestTrade.SIDE_BUY.equals(order.side()) && !order.orderDate().isAfter(day)) {
                executeBuy(order, pending, portfolio, priceBook, instruments, cost, day, days, dayIndex);
            }
        }
    }

    private void executeSell(Order order, List<Order> pending, Portfolio portfolio,
                             PriceBook priceBook, Map<String, Instrument> instruments,
                             TradingCost cost, LocalDate day, List<LocalDate> days, int dayIndex) {
        Position position = portfolio.positions().get(order.instrumentId());
        if (position == null) {
            order.expire(BacktestTrade.REASON_CANCELLED);
            pending.remove(order);
            return;
        }
        DailyBar bar = priceBook.barOn(order.instrumentId(), day);
        if (bar == null) {
            order.defer(nextDay(days, dayIndex, day), BacktestTrade.REASON_SUSPENDED);
            return;
        }
        if (oneWord(bar, instruments.get(order.instrumentId()), false)) {
            order.defer(nextDay(days, dayIndex, day), BacktestTrade.REASON_LIMIT_DOWN);
            return;
        }
        BigDecimal price = cost.sellPrice(BigDecimal.valueOf(bar.open()));
        BigDecimal amount = price.multiply(BigDecimal.valueOf(position.shares()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = cost.sellFee(amount);
        BigDecimal net = amount.subtract(fee);
        BigDecimal pnl = net.subtract(position.cost()).setScale(2, RoundingMode.HALF_UP);
        portfolio.remove(order.instrumentId());
        portfolio.addCash(net);
        order.fill(day, price.doubleValue(), position.shares(),
                amount.doubleValue(), fee.doubleValue(), pnl.doubleValue());
        pending.remove(order);
    }

    private void executeBuy(Order order, List<Order> pending, Portfolio portfolio,
                            PriceBook priceBook, Map<String, Instrument> instruments,
                            TradingCost cost, LocalDate day, List<LocalDate> days, int dayIndex) {
        if (portfolio.holds(order.instrumentId())) {
            order.expire(BacktestTrade.REASON_CANCELLED);
            pending.remove(order);
            return;
        }
        DailyBar bar = priceBook.barOn(order.instrumentId(), day);
        if (bar == null) {
            order.defer(nextDay(days, dayIndex, day), BacktestTrade.REASON_SUSPENDED);
            return;
        }
        if (oneWord(bar, instruments.get(order.instrumentId()), true)) {
            order.defer(nextDay(days, dayIndex, day), BacktestTrade.REASON_LIMIT_UP);
            return;
        }

        long remainingBuys = pending.stream()
                .filter(it -> BacktestTrade.SIDE_BUY.equals(it.side()) && !it.orderDate().isAfter(day))
                .count();
        BigDecimal budget = portfolio.cash()
                .divide(BigDecimal.valueOf(Math.max(1, remainingBuys)), 2, RoundingMode.DOWN);
        BigDecimal price = cost.buyPrice(BigDecimal.valueOf(bar.open()));
        long shares = budget.divide(price, 0, RoundingMode.DOWN).longValue() / LOT_SIZE * LOT_SIZE;

        BigDecimal amount = ZERO;
        BigDecimal fee = ZERO;
        while (shares > 0) {
            amount = price.multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
            fee = cost.buyFee(amount);
            if (amount.add(fee).compareTo(portfolio.cash()) <= 0) {
                break;
            }
            shares -= LOT_SIZE;
        }
        if (shares == 0) {
            order.expire(BacktestTrade.REASON_CASH);
            pending.remove(order);
            return;
        }

        BigDecimal total = amount.add(fee);
        portfolio.subtractCash(total);
        portfolio.add(order.instrumentId(), order.code(), order.name(), shares, total);
        order.fill(day, price.doubleValue(), shares, amount.doubleValue(), fee.doubleValue(), null);
        pending.remove(order);
    }

    /** 调仓：不在目标内的持仓卖出，目标内未持有的买入；与新目标冲突的挂单作废。 */
    private long rebalance(List<Order> pending, List<Order> orders, Portfolio portfolio,
                           List<ScreenerRow> targets, LocalDate signal, LocalDate executionDay,
                           long orderSeq) {
        Set<String> targetIds = targets.stream()
                .map(ScreenerRow::instrumentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        pending.removeIf(order -> {
            boolean cancelBuy = BacktestTrade.SIDE_BUY.equals(order.side())
                    && !targetIds.contains(order.instrumentId());
            boolean cancelSell = BacktestTrade.SIDE_SELL.equals(order.side())
                    && targetIds.contains(order.instrumentId());
            if (cancelBuy || cancelSell) {
                order.expire(BacktestTrade.REASON_CANCELLED);
                return true;
            }
            return false;
        });

        for (Position position : List.copyOf(portfolio.positions().values())) {
            if (targetIds.contains(position.instrumentId())) {
                continue;
            }
            boolean alreadyPending = pending.stream().anyMatch(order ->
                    BacktestTrade.SIDE_SELL.equals(order.side())
                            && order.instrumentId().equals(position.instrumentId()));
            if (!alreadyPending) {
                Order order = new Order(++orderSeq, position.instrumentId(), position.code(),
                        position.name(), BacktestTrade.SIDE_SELL, signal, executionDay);
                pending.add(order);
                orders.add(order);
            }
        }

        for (ScreenerRow target : targets) {
            if (portfolio.holds(target.instrumentId())) {
                continue;
            }
            boolean alreadyPending = pending.stream().anyMatch(order ->
                    BacktestTrade.SIDE_BUY.equals(order.side())
                            && order.instrumentId().equals(target.instrumentId()));
            if (!alreadyPending) {
                Order order = new Order(++orderSeq, target.instrumentId(), target.code(),
                        target.name(), BacktestTrade.SIDE_BUY, signal, executionDay);
                pending.add(order);
                orders.add(order);
            }
        }
        return orderSeq;
    }

    private BigDecimal marketValue(Portfolio portfolio, PriceBook priceBook, LocalDate day) {
        BigDecimal value = ZERO;
        for (Position position : portfolio.positions().values()) {
            Double close = priceBook.closeAt(position.instrumentId(), day);
            if (close == null) {
                throw new IllegalStateException(
                        "持仓缺少可用收盘价，数据异常：" + position.instrumentId());
            }
            value = value.add(BigDecimal.valueOf(close)
                    .multiply(BigDecimal.valueOf(position.shares())));
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean oneWord(DailyBar bar, Instrument instrument, boolean up) {
        if (instrument == null || bar.preClose() == null || !bar.isFlatPrice()) {
            return false;
        }
        double ratio = BoardLimit.ratio(instrument.board(), isRiskWarned(instrument.name()));
        double limit = up
                ? BoardLimit.limitUp(bar.preClose(), ratio)
                : BoardLimit.limitDown(bar.preClose(), ratio);
        return BoardLimit.atLimit(bar.open(), limit);
    }

    private boolean isRiskWarned(String name) {
        return name != null && name.contains("ST");
    }

    private LocalDate nextDay(List<LocalDate> days, int dayIndex, LocalDate current) {
        return dayIndex + 1 < days.size() ? days.get(dayIndex + 1) : current;
    }

    /** 信号日 = 每个周 / 月 / 季度的最后一个交易日。 */
    private Set<LocalDate> signalDates(List<LocalDate> days, RebalanceFrequency frequency) {
        Set<LocalDate> result = new LinkedHashSet<>();
        for (int i = 0; i < days.size(); i++) {
            LocalDate current = days.get(i);
            LocalDate next = i + 1 < days.size() ? days.get(i + 1) : null;
            if (next == null || crossesPeriod(current, next, frequency)) {
                result.add(current);
            }
        }
        return result;
    }

    private boolean crossesPeriod(LocalDate current, LocalDate next, RebalanceFrequency frequency) {
        return switch (frequency) {
            case DAILY -> true;
            case WEEKLY -> weekStart(current).isBefore(weekStart(next));
            case MONTHLY -> YearMonth.from(current).isBefore(YearMonth.from(next));
            case QUARTERLY -> quarter(current) != quarter(next);
        };
    }

    private LocalDate weekStart(LocalDate date) {
        WeekFields weekFields = WeekFields.ISO;
        return date.with(weekFields.dayOfWeek(), 1);
    }

    private int quarter(LocalDate date) {
        return date.getYear() * 4 + (date.getMonthValue() - 1) / 3;
    }
}
