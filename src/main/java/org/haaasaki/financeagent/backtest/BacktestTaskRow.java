package org.haaasaki.financeagent.backtest;

import java.time.LocalDate;

import org.haaasaki.financeagent.backtest.engine.RebalanceFrequency;
import org.haaasaki.financeagent.data.model.BacktestTask;

/**
 * 回测记录行：任务快照 + 从 {@code params_json} 解析的关键参数（调仓频率 / 持仓数 / 回测区间）。
 *
 * <p>参数损坏或缺失时对应字段为 null，页面显示 {@code --}，不影响整页渲染。
 *
 * @param task      任务快照
 * @param frequency 调仓频率；参数缺失为 null
 * @param topN      目标持仓数；参数缺失为 null
 * @param startDate 回测开始交易日；参数缺失为 null
 * @param endDate   回测结束交易日；参数缺失为 null
 */
public record BacktestTaskRow(
        BacktestTask task,
        RebalanceFrequency frequency,
        Integer topN,
        LocalDate startDate,
        LocalDate endDate) {

    /** 调仓频率中文标签，缺失显示 --（模板调用）。 */
    public String frequencyLabel() {
        return frequency == null ? "--" : frequency.label();
    }

    /** 回测区间文本 {@code 开始 ~ 结束}，缺失显示 --。 */
    public String period() {
        return startDate == null || endDate == null ? "--" : startDate + " ~ " + endDate;
    }

    /** 持仓数文本，缺失显示 --。 */
    public String topNLabel() {
        return topN == null ? "--" : String.valueOf(topN);
    }
}
