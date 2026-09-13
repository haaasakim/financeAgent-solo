package org.haaasaki.financeagent.backtest;

import java.time.LocalDateTime;
import java.util.List;

import org.haaasaki.financeagent.backtest.engine.BacktestParams;
import org.haaasaki.financeagent.backtest.engine.BacktestResult;
import org.haaasaki.financeagent.backtest.metric.BacktestMetrics;
import org.haaasaki.financeagent.backtest.metric.PerformanceMetrics;
import org.haaasaki.financeagent.data.mapper.BacktestDailyMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTaskMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTradeMapper;
import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTrade;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回测结果落库：日度序列、订单流水与绩效快照在同一个事务内写入，
 * 失败不留下半截结果。
 */
@Component
public class BacktestResultWriter {

    private static final int BATCH = 500;

    private final BacktestDailyMapper backtestDailyMapper;
    private final BacktestTradeMapper backtestTradeMapper;
    private final BacktestTaskMapper backtestTaskMapper;

    public BacktestResultWriter(BacktestDailyMapper backtestDailyMapper,
                                BacktestTradeMapper backtestTradeMapper,
                                BacktestTaskMapper backtestTaskMapper) {
        this.backtestDailyMapper = backtestDailyMapper;
        this.backtestTradeMapper = backtestTradeMapper;
        this.backtestTaskMapper = backtestTaskMapper;
    }

    @Transactional
    public void persist(long taskId, BacktestParams params, BacktestResult result) {
        insertDaily(result.daily());
        insertTrades(result.trades());

        BacktestMetrics metrics = PerformanceMetrics.compute(result.daily(), params.initialCapital(),
                result.trades(), params.startDate(), params.endDate());
        backtestTaskMapper.markSuccess(taskId, result.daily().size(),
                metrics.finalNav(), metrics.cumulativeReturn(), metrics.annualReturn(),
                metrics.maxDrawdown(), metrics.sharpe(), metrics.winRate(), metrics.turnover(),
                LocalDateTime.now().toString());
    }

    private void insertDaily(List<BacktestDaily> daily) {
        for (int i = 0; i < daily.size(); i += BATCH) {
            backtestDailyMapper.batchInsert(daily.subList(i, Math.min(daily.size(), i + BATCH)));
        }
    }

    private void insertTrades(List<BacktestTrade> trades) {
        for (int i = 0; i < trades.size(); i += BATCH) {
            backtestTradeMapper.batchInsert(trades.subList(i, Math.min(trades.size(), i + BATCH)));
        }
    }
}
