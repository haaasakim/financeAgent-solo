package org.haaasaki.financeagent.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.backtest.engine.BacktestParams;
import org.haaasaki.financeagent.backtest.engine.RebalanceFrequency;
import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.BacktestDailyMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTaskMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTradeMapper;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.StrategyMapper;
import org.haaasaki.financeagent.data.mapper.ValuationDailyMapper;
import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTask;
import org.haaasaki.financeagent.data.model.BacktestTrade;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.ScreenerService;
import org.haaasaki.financeagent.screener.dsl.Condition;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 回测服务：参数校验、任务创建与提交、结果查询。
 *
 * <p>执行在专用线程池（{@link BacktestRunner}），创建任务的事务提交后才会投递，
 * 避免异步线程读不到任务行。参数与策略 hash 全部落库作为复现依据。
 */
@Service
public class BacktestService {

    /** 引擎版本：2 起建仓口径为固定 N 槽等权（WI-009），3 起支持出场风控（WI-010）。 */
    /** 引擎版本：4 = 增加前复权失真价格防护（WI-013）；3 及以下的结果可能与假成交混用。 */
    public static final String ENGINE_VERSION = "4";
    public static final int TASK_PAGE_SIZE = 20;
    public static final int TRADE_PAGE_SIZE = 50;
    public static final int TRIP_PAGE_SIZE = 50;

    private static final int MAX_TOP_N = 100;
    private static final BigDecimal MIN_CAPITAL = new BigDecimal("1000");
    private static final BigDecimal MAX_PCT = new BigDecimal("1");
    private static final BigDecimal MAX_SLIPPAGE_BPS = new BigDecimal("100");
    private static final BigDecimal MAX_STOP_LOSS_PCT = new BigDecimal("50");
    private static final int MAX_TIME_STOP_DAYS = 250;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    /** 依赖 valuation_daily 历史序列的因子（WI-007）。 */
    private static final Set<String> VALUATION_FACTORS = Set.of("peTtm", "pb");

    private final BacktestTaskMapper backtestTaskMapper;
    private final BacktestDailyMapper backtestDailyMapper;
    private final BacktestTradeMapper backtestTradeMapper;
    private final StrategyMapper strategyMapper;
    private final InstrumentMapper instrumentMapper;
    private final DailyBarMapper dailyBarMapper;
    private final ValuationDailyMapper valuationDailyMapper;
    private final ScreenerService screenerService;
    private final BacktestRunner backtestRunner;
    private final ObjectMapper objectMapper;

    public BacktestService(BacktestTaskMapper backtestTaskMapper,
                           BacktestDailyMapper backtestDailyMapper,
                           BacktestTradeMapper backtestTradeMapper,
                           StrategyMapper strategyMapper,
                           InstrumentMapper instrumentMapper,
                           DailyBarMapper dailyBarMapper,
                           ValuationDailyMapper valuationDailyMapper,
                           ScreenerService screenerService,
                           BacktestRunner backtestRunner,
                           ObjectMapper objectMapper) {
        this.backtestTaskMapper = backtestTaskMapper;
        this.backtestDailyMapper = backtestDailyMapper;
        this.backtestTradeMapper = backtestTradeMapper;
        this.strategyMapper = strategyMapper;
        this.instrumentMapper = instrumentMapper;
        this.dailyBarMapper = dailyBarMapper;
        this.valuationDailyMapper = valuationDailyMapper;
        this.screenerService = screenerService;
        this.backtestRunner = backtestRunner;
        this.objectMapper = objectMapper;
    }

    /** 校验并创建任务；事务提交后投递异步执行，返回任务 id。 */
    @Transactional
    public long createTask(BacktestRequest request) {
        LocalDate dataThrough = dataThrough()
                .orElseThrow(() -> new BusinessException("尚无日K数据，无法回测"));
        Strategy strategy = strategyMapper.findById(request.strategyId())
                .orElseThrow(() -> new BusinessException("策略不存在"));
        ScreenerDsl dsl = screenerService.parse(strategy.dsl());
        screenerService.validateBacktest(dsl);
        validateValuationCoverage(dsl, request.startDate());
        String hash = StrategyHash.of(dsl, objectMapper);

        BacktestParams params = toParams(request, dataThrough, strategy.name(), hash);
        BacktestTask task = new BacktestTask(null, strategy.id(), strategy.name(), hash,
                ENGINE_VERSION, toJson(params), BacktestTask.STATUS_PENDING,
                0, 0, null, dataThrough, params.benchmarkId(),
                params.initialCapital().doubleValue(),
                null, null, null, null, null, null, null,
                LocalDateTime.now().toString(), null, null);
        backtestTaskMapper.insert(task);
        long taskId = backtestTaskMapper.lastInsertId();

        submitAfterCommit(taskId);
        return taskId;
    }

    private BacktestParams toParams(BacktestRequest request, LocalDate dataThrough,
                                    String strategyName, String strategyHash) {
        if (request.strategyId() <= 0) {
            throw new BusinessException("请选择策略");
        }
        if (request.startDate() == null || request.endDate() == null
                || !request.startDate().isBefore(request.endDate())) {
            throw new BusinessException("回测区间不合法：开始日期必须早于结束日期");
        }
        LocalDate end = request.endDate();
        if (end.isAfter(dataThrough)) {
            throw new BusinessException("结束日期晚于最新数据日期 " + dataThrough + "，请下调区间");
        }
        if (request.topN() < 1 || request.topN() > MAX_TOP_N) {
            throw new BusinessException("持仓数量必须在 1 ~ " + MAX_TOP_N + " 之间");
        }
        if (request.initialCapital() == null
                || request.initialCapital().compareTo(MIN_CAPITAL) < 0) {
            throw new BusinessException("初始资金不能低于 " + MIN_CAPITAL + " 元");
        }
        BigDecimal commission = pct(request.commissionRatePct(), "佣金率");
        BigDecimal stamp = pct(request.stampTaxPct(), "印花税");
        BigDecimal slippage = bps(request.slippageBps());
        BigDecimal stopLoss = stopLossRate(request.stopLossPct());
        int timeStopDays = timeStopDays(request.timeStopDays());
        String benchmarkId = request.benchmarkId();
        boolean benchmarkExists = instrumentMapper.findByType(Instrument.TYPE_INDEX).stream()
                .anyMatch(index -> index.instrumentId().equals(benchmarkId));
        if (!benchmarkExists) {
            throw new BusinessException("基准必须是已同步的指数");
        }
        if (dailyBarMapper.countByInstrument(benchmarkId) == 0) {
            throw new BusinessException("基准「" + benchmarkId + "」没有日K数据");
        }
        RebalanceFrequency frequency;
        try {
            frequency = RebalanceFrequency.from(request.frequency());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("调仓频率不合法");
        }
        return new BacktestParams(request.strategyId(), strategyName, strategyHash,
                request.startDate(), end, frequency, request.topN(),
                request.initialCapital().setScale(2, RoundingMode.HALF_UP),
                commission, new BigDecimal("5.00"), stamp, slippage,
                stopLoss, timeStopDays, benchmarkId, dataThrough);
    }

    /** 固定止损：页面百分数 → 小数；0 关闭。 */
    private BigDecimal stopLossRate(BigDecimal pct) {
        BigDecimal v = pct == null ? BigDecimal.ZERO : pct;
        if (v.signum() < 0 || v.compareTo(MAX_STOP_LOSS_PCT) > 0) {
            throw new BusinessException("止损比例必须在 0% ~ " + MAX_STOP_LOSS_PCT + "% 之间");
        }
        return v.divide(HUNDRED, 8, RoundingMode.HALF_UP);
    }

    /** 时间止损：0 关闭，上限 250 个交易日（约一年）。 */
    private int timeStopDays(int days) {
        if (days < 0 || days > MAX_TIME_STOP_DAYS) {
            throw new BusinessException("时间止损必须在 0 ~ " + MAX_TIME_STOP_DAYS + " 个交易日之间");
        }
        return days;
    }

    private BigDecimal pct(BigDecimal value, String label) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0 || v.compareTo(MAX_PCT) > 0) {
            throw new BusinessException(label + "必须在 0% ~ 1% 之间");
        }
        return v.divide(HUNDRED, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal bps(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0 || v.compareTo(MAX_SLIPPAGE_BPS) > 0) {
            throw new BusinessException("滑点必须在 0 ~ " + MAX_SLIPPAGE_BPS + " bps 之间");
        }
        return v.divide(TEN_THOUSAND, 8, RoundingMode.HALF_UP);
    }

    private String toJson(BacktestParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("回测参数序列化失败", e);
        }
    }

    private void submitAfterCommit(long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    backtestRunner.run(taskId);
                }
            });
        } else {
            backtestRunner.run(taskId);
        }
    }

    /**
     * 估值因子的数据边界校验（WI-007）：东财历史约从 2017 年起，
     * 区间早于最早估值日期时给出提示，避免回测出「全空命中」。
     */
    private void validateValuationCoverage(ScreenerDsl dsl, LocalDate startDate) {
        boolean usesValuation = Stream.of(dsl.all(), dsl.any(), dsl.not())
                .flatMap(List::stream)
                .map(Condition::factor)
                .anyMatch(VALUATION_FACTORS::contains);
        if (!usesValuation) {
            return;
        }
        LocalDate earliest = valuationDailyMapper.findGlobalMinTradeDate()
                .orElseThrow(() -> new BusinessException(
                        "估值历史尚未回填，含 PE/PB 的策略暂不可回测（可先执行 --valuation.init）"));
        if (startDate.isBefore(earliest)) {
            throw new BusinessException("估值数据自 " + earliest + " 起，开始日期请不早于该日");
        }
    }

    public BacktestTask task(long id) {
        return backtestTaskMapper.findById(id)
                .orElseThrow(() -> new BusinessException("回测任务不存在"));
    }

    /** 任务保存的回测参数，用于历史回测入参回显；任务不存在或参数解析失败返回空。 */
    public java.util.Optional<BacktestParams> params(long taskId) {
        return backtestTaskMapper.findById(taskId).flatMap(task -> {
            try {
                return java.util.Optional.of(objectMapper.readValue(task.paramsJson(), BacktestParams.class));
            } catch (JsonProcessingException e) {
                return java.util.Optional.empty();
            }
        });
    }

    /** 回测记录分页视图：全部任务，可按编号 / 收益 / 回撤 / 夏普排序。 */
    public BacktestTaskView recentTasks(int page, BacktestTaskSort sort, boolean desc) {
        int currentPage = Math.max(1, page);
        long total = backtestTaskMapper.countAll();
        int offset = (currentPage - 1) * TASK_PAGE_SIZE;
        List<BacktestTaskRow> rows = offset >= total
                ? List.of()
                : backtestTaskMapper.findPage(sort.column(), desc ? "DESC" : "ASC",
                        TASK_PAGE_SIZE, offset).stream().map(this::toRow).toList();
        return new BacktestTaskView(rows, total, currentPage, TASK_PAGE_SIZE, sort, desc);
    }

    /** 解析任务参数用于记录列表展示；参数损坏或缺失时保持缺失（页面显示 --）。 */
    private BacktestTaskRow toRow(BacktestTask task) {
        if (task.paramsJson() == null || task.paramsJson().isBlank()) {
            return new BacktestTaskRow(task, null, null, null, null);
        }
        try {
            BacktestParams params = objectMapper.readValue(task.paramsJson(), BacktestParams.class);
            return new BacktestTaskRow(task, params.frequency(), params.topN(),
                    params.startDate(), params.endDate());
        } catch (JsonProcessingException e) {
            return new BacktestTaskRow(task, null, null, null, null);
        }
    }

    public List<Strategy> strategies() {
        return strategyMapper.findAll();
    }

    public List<Instrument> benchmarks() {
        return instrumentMapper.findByType(Instrument.TYPE_INDEX);
    }

    public java.util.Optional<LocalDate> dataThrough() {
        return dailyBarMapper.findGlobalMaxTradeDate();
    }

    /** 结果视图：任务 + 日度序列 + 指定页订单流水；未成功任务拒绝查询。 */
    public BacktestResultView result(long taskId, int page) {
        BacktestTask task = task(taskId);
        if (!BacktestTask.STATUS_SUCCESS.equals(task.status())) {
            throw new BusinessException("任务尚未成功完成，无法查看结果");
        }
        int currentPage = Math.max(1, page);
        List<BacktestTrade> trades =
                backtestTradeMapper.findPage(taskId,
                        (currentPage - 1) * TRADE_PAGE_SIZE, TRADE_PAGE_SIZE);
        long total = backtestTradeMapper.countByTask(taskId);
        return new BacktestResultView(task, backtestDailyMapper.findByTask(taskId),
                trades, total, currentPage, TRADE_PAGE_SIZE);
    }

    /**
     * 按标的配对的往返记录视图；与订单流水同一成功状态校验与分页口径，
     * 支持代码/名称与买卖日搜索、白名单排序；费用合计按命中集合统计。
     */
    public RoundTripView roundTrips(long taskId, int page, RoundTripSort sort, boolean desc,
                                    RoundTripQuery query) {
        BacktestTask task = task(taskId);
        if (!BacktestTask.STATUS_SUCCESS.equals(task.status())) {
            throw new BusinessException("任务尚未成功完成，无法查看结果");
        }
        RoundTripQuery effective = query == null ? RoundTripQuery.EMPTY : query;
        List<BacktestTrade> filled = backtestTradeMapper.findFilledByTask(taskId);
        List<LocalDate> tradingDates = backtestDailyMapper.findByTask(taskId).stream()
                .map(BacktestDaily::tradeDate)
                .toList();
        List<RoundTrip> all = new ArrayList<>(TradePairing.pair(filled, tradingDates));
        all.removeIf(trip -> !effective.matches(trip));
        all.sort(sort.comparator(desc));
        double feeTotal = all.stream().mapToDouble(RoundTrip::feeTotal).sum();

        int currentPage = Math.max(1, page);
        int offset = (currentPage - 1) * TRIP_PAGE_SIZE;
        List<RoundTrip> rows = offset >= all.size()
                ? List.of()
                : List.copyOf(all.subList(offset, Math.min(offset + TRIP_PAGE_SIZE, all.size())));
        return new RoundTripView(task, rows, all.size(), currentPage, TRIP_PAGE_SIZE, sort, desc,
                effective, feeTotal);
    }
}
