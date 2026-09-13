package org.haaasaki.financeagent.backtest;

import java.util.List;

import org.haaasaki.financeagent.data.model.BacktestTask;

/**
 * 「按标的配对」分页视图：给回测结果页展示往返记录。
 *
 * @param task  任务
 * @param trips 当前页往返记录
 * @param total 往返记录总数（含期末持有）
 * @param page  当前页（从 1 开始）
 * @param size  每页条数
 * @param sort  当前排序字段
 * @param desc  是否降序
 */
public record RoundTripView(
        BacktestTask task,
        List<RoundTrip> trips,
        long total,
        int page,
        int size,
        RoundTripSort sort,
        boolean desc) {

    public int totalPages() {
        return total == 0 ? 1 : (int) ((total + size - 1) / size);
    }

    public boolean hasPrev() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages();
    }

    /** 当前排序列是否是指定列，供表头高亮。 */
    public boolean isSortedBy(RoundTripSort field) {
        return sort == field;
    }

    /** 点击指定列后应切换到的方向：同列点击翻转，其他列数值默认降序、其余升序。 */
    public String nextDirection(RoundTripSort field) {
        if (isSortedBy(field)) {
            return desc ? "asc" : "desc";
        }
        return field.numeric() ? "desc" : "asc";
    }
}
