package org.haaasaki.financeagent.backtest.engine;

/**
 * 回测进度回调：引擎先报区间交易日总数，再逐交易日回报进度。
 */
public interface BacktestProgress {

    void total(int total);

    void done(int done);
}
