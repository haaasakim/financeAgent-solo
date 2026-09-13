package org.haaasaki.financeagent.backtest.engine;

import java.util.List;

import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTrade;

/**
 * 引擎输出：日度净值序列与订单流水（含未成交），绩效由服务层另行计算。
 *
 * @param daily  日度净值，按交易日升序
 * @param trades 订单流水，含 FILLED 与 EXPIRED
 */
public record BacktestResult(
        List<BacktestDaily> daily,
        List<BacktestTrade> trades) {
}
