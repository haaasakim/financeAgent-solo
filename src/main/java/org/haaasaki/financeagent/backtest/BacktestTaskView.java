package org.haaasaki.financeagent.backtest;

import java.util.List;

/**
 * 回测记录分页视图：全部任务，按白名单字段排序。
 *
 * @param tasks 当前页记录（任务 + 关键参数）
 * @param total 任务总数
 * @param page  当前页（从 1 开始）
 * @param size  每页条数
 * @param sort  当前排序字段
 * @param desc  是否降序
 */
public record BacktestTaskView(
        List<BacktestTaskRow> tasks,
        long total,
        int page,
        int size,
        BacktestTaskSort sort,
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

    public boolean isSortedBy(BacktestTaskSort field) {
        return sort == field;
    }

    /** 点击表头后的方向：当前列翻转，其他列默认降序。 */
    public boolean nextDirection(BacktestTaskSort field) {
        return isSortedBy(field) ? !desc : true;
    }

    /** 模板用：按 URL 键判断当前排序列（键非法不匹配）。 */
    public boolean isSortedBy(String key) {
        return key != null && sort.key().equalsIgnoreCase(key);
    }

    /** 模板用：按 URL 键取点击后的方向。 */
    public boolean nextDirection(String key) {
        return isSortedBy(key) ? !desc : true;
    }
}
