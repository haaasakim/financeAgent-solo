package org.haaasaki.financeagent.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.haaasaki.financeagent.data.model.BacktestTask;
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

    public static final String ENGINE_VERSION = "1";
    private static final int RECENT_LIMIT = 10;
    public static final int TRADE_PAGE_SIZE = 50;

    private static final int MAX_TOP_N = 100;
    private static final BigDecimal MIN_CAPITAL = new BigDecimal("1000");
    private static final BigDecimal MAX_PCT = new BigDecimal("1");
    private static final BigDecimal MAX_SLIPPAGE_BPS = new BigDecimal("100");
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
                benchmarkId, dataThrough);
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

    public List<BacktestTask> recentTasks() {
        return backtestTaskMapper.findRecent(RECENT_LIMIT);
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
        List<org.haaasaki.financeagent.data.model.BacktestTrade> trades =
                backtestTradeMapper.findPage(taskId,
                        (currentPage - 1) * TRADE_PAGE_SIZE, TRADE_PAGE_SIZE);
        long total = backtestTradeMapper.countByTask(taskId);
        return new BacktestResultView(task, backtestDailyMapper.findByTask(taskId),
                trades, total, currentPage, TRADE_PAGE_SIZE);
    }
}
