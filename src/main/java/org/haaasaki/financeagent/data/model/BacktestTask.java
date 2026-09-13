package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 回测运行记录（{@code backtest_task} 表），同时充当异步任务记录。
 *
 * <p>每次回测创建一条新记录，结果按 {@code id} 归属，重复执行不覆盖历史。
 * 绩效字段为运行结束时快照，小数口径见 docs/work-items/WI-006-回测模块.md 第 2.3 节。
 *
 * @param id               任务 id
 * @param strategyId       触发时策略 id；策略被删后保留原值
 * @param strategyName     运行时的策略名快照
 * @param strategyHash     策略 DSL 规范化 JSON 的 SHA-256，复现依据
 * @param engineVersion    引擎版本
 * @param paramsJson       全部参数与数据快照标识（JSON）
 * @param status           PENDING / RUNNING / SUCCESS / FAILED
 * @param progressDone     已处理交易日数
 * @param progressTotal    区间交易日总数
 * @param message          失败原因摘要，成功时为 null
 * @param dataThrough      运行开始时 daily_bar 最大交易日（数据快照标识）
 * @param benchmarkId      基准 instrument_id
 * @param initialCapital   初始资金，元
 * @param finalNav         最终净值，元
 * @param cumulativeReturn 累计收益，小数
 * @param annualReturn     年化收益，小数
 * @param maxDrawdown      最大回撤，负数小数
 * @param sharpe           夏普比率，无风险利率取 0；样本不足时为 null
 * @param winRate          已平仓交易胜率，小数
 * @param turnover         年化换手率，小数
 * @param createdAt        创建时刻，ISO 文本
 * @param startedAt        开始执行时刻，ISO 文本
 * @param finishedAt       结束时刻，ISO 文本
 */
public record BacktestTask(
        Long id,
        Long strategyId,
        String strategyName,
        String strategyHash,
        String engineVersion,
        String paramsJson,
        String status,
        int progressDone,
        int progressTotal,
        String message,
        LocalDate dataThrough,
        String benchmarkId,
        Double initialCapital,
        Double finalNav,
        Double cumulativeReturn,
        Double annualReturn,
        Double maxDrawdown,
        Double sharpe,
        Double winRate,
        Double turnover,
        String createdAt,
        String startedAt,
        String finishedAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}
