package org.haaasaki.financeagent.backtest;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.backtest.engine.BacktestEngine;
import org.haaasaki.financeagent.backtest.engine.BacktestParams;
import org.haaasaki.financeagent.backtest.engine.BacktestProgress;
import org.haaasaki.financeagent.backtest.engine.BacktestResult;
import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.BacktestTaskMapper;
import org.haaasaki.financeagent.data.mapper.StrategyMapper;
import org.haaasaki.financeagent.data.model.BacktestTask;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.ScreenerService;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 回测异步执行器：加载任务与策略、调用引擎、写回状态。
 *
 * <p>策略在创建任务时已做过一次校验与 hash；执行前再比对一次 hash，
 * 防止任务排队期间策略被修改导致「跑的不是提交的那份策略」。
 */
@Component
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
    private static final int PROGRESS_INTERVAL = 20;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final BacktestTaskMapper backtestTaskMapper;
    private final StrategyMapper strategyMapper;
    private final ScreenerService screenerService;
    private final BacktestEngine backtestEngine;
    private final BacktestResultWriter backtestResultWriter;
    private final ObjectMapper objectMapper;

    public BacktestRunner(BacktestTaskMapper backtestTaskMapper,
                          StrategyMapper strategyMapper,
                          ScreenerService screenerService,
                          BacktestEngine backtestEngine,
                          BacktestResultWriter backtestResultWriter,
                          ObjectMapper objectMapper) {
        this.backtestTaskMapper = backtestTaskMapper;
        this.strategyMapper = strategyMapper;
        this.screenerService = screenerService;
        this.backtestEngine = backtestEngine;
        this.backtestResultWriter = backtestResultWriter;
        this.objectMapper = objectMapper;
    }

    @Async("backtestExecutor")
    public void run(long taskId) {
        BacktestTask task = backtestTaskMapper.findById(taskId).orElse(null);
        if (task == null) {
            log.error("回测任务 {} 不存在，跳过执行", taskId);
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            backtestTaskMapper.markRunning(taskId, 0, LocalDateTime.now().toString());

            Strategy strategy = strategyMapper.findById(task.strategyId())
                    .orElseThrow(() -> new BusinessException("策略已删除，无法回测"));
            ScreenerDsl dsl = screenerService.parse(strategy.dsl());
            if (!StrategyHash.of(dsl, objectMapper).equals(task.strategyHash())) {
                throw new BusinessException("策略已变更，请重新发起回测");
            }
            BacktestParams params = objectMapper.readValue(task.paramsJson(), BacktestParams.class);

            int[] persisted = {0};
            BacktestProgress progress = new BacktestProgress() {
                @Override
                public void total(int total) {
                    backtestTaskMapper.updateProgressTotal(taskId, total);
                }

                @Override
                public void done(int done) {
                    if (done - persisted[0] >= PROGRESS_INTERVAL) {
                        backtestTaskMapper.updateProgress(taskId, done);
                        persisted[0] = done;
                    }
                }
            };

            BacktestResult result = backtestEngine.run(taskId, params, dsl, progress);
            backtestResultWriter.persist(taskId, params, result);
            log.info("回测任务 {} 完成：策略「{}」区间 {} ~ {}，交易日 {}，耗时 {} ms",
                    taskId, strategy.name(), params.startDate(), params.endDate(),
                    result.daily().size(), System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.warn("回测任务 {} 失败：{}", taskId, e.getMessage());
            backtestTaskMapper.markFailed(taskId, message(e), LocalDateTime.now().toString());
        }
    }

    private String message(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
