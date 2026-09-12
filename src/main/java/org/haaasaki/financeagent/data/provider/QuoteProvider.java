package org.haaasaki.financeagent.data.provider;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.data.model.DailyBar;

/**
 * 行情数据源接口。
 *
 * <p>实现约定：
 * <ul>
 *   <li>返回结果按 {@code tradeDate} <b>升序</b>排列，且已完成单位换算（价格为元、成交量为股、百分比为小数）</li>
 *   <li>标的无数据时返回<b>空列表</b>，不抛异常（例如上市时间晚于查询区间）</li>
 *   <li>限速、重试、超时由实现内部的 {@code ThrottledHttpClient} 处理，调用方无感</li>
 *   <li>返回条数与预期明显不符时抛 {@code DataIntegrityException}，<b>不返回部分数据</b></li>
 * </ul>
 *
 * <p>具体接口规格见 docs/DATA_SOURCES.md。
 */
public interface QuoteProvider {

    /** 数据源标识，用于写入 {@code daily_bar.source} 与同步日志。 */
    String name();

    /**
     * 拉取指定标的在 {@code [start, end]} 区间内的日K。
     *
     * @param instrumentId 统一标的标识，如 {@code 600000.SH}
     * @param start        起始日期（含）
     * @param end          结束日期（含）
     * @return 按日期升序的日K列表，无数据时为空列表
     */
    List<DailyBar> fetchDailyBars(String instrumentId, LocalDate start, LocalDate end);
}
