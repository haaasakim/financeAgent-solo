package org.haaasaki.financeagent.backtest;

import java.util.List;

import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTask;
import org.haaasaki.financeagent.data.model.BacktestTrade;

/**
 * 回测结果视图：直接给模板用于指标卡、图表与交易流水。
 *
 * @param task         任务（含绩效快照）
 * @param daily        日度序列，升序
 * @param trades       当前页订单流水
 * @param tradeTotal   订单总数
 * @param page         当前页（从 1 开始）
 * @param size         每页条数
 */
public record BacktestResultView(
        BacktestTask task,
        List<BacktestDaily> daily,
        List<BacktestTrade> trades,
        long tradeTotal,
        int page,
        int size) {
}
