package org.haaasaki.financeagent.market;

import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.model.MarketRow;

/**
 * 市场列表分页结果。
 *
 * @param rows  当前页的标的投影（快照缺失时价格类字段为 null）
 * @param total 满足筛选条件的总条数
 * @param query 本次查询条件，供模板构建排序与翻页链接
 */
public record MarketListResult(List<MarketRow> rows, long total, MarketListQuery query) {

    public int totalPages() {
        return total == 0 ? 1 : (int) ((total + query.size() - 1) / query.size());
    }

    public boolean hasPrev() {
        return query.page() > 1;
    }

    public boolean hasNext() {
        return query.page() < totalPages();
    }

    public long from() {
        return total == 0 ? 0 : (long) (query.page() - 1) * query.size() + 1;
    }

    public long to() {
        return Math.min(total, (long) query.page() * query.size());
    }

    /** 是否处于筛选状态（用于空结果文案区分「无匹配」与「尚无快照」）。 */
    public boolean filtered() {
        return query.keyword() != null || query.board() != null || query.instrumentType() != null;
    }

    /** 当前排序列是否是指定列，供表头高亮。 */
    public boolean isSortedBy(MarketSortField field) {
        return query.sort() == field;
    }

    /** 点击指定列后应切换到的方向：同列点击翻转，其他列默认升序。 */
    public String nextDirection(MarketSortField field) {
        return isSortedBy(field) && !query.desc() ? "desc" : "asc";
    }

    /** 页码窗口：当前页前后各 2 页，最多 5 个。 */
    public List<Integer> pageWindow() {
        int totalPages = totalPages();
        int start = Math.max(1, query.page() - 2);
        int end = Math.min(totalPages, start + 4);
        start = Math.max(1, end - 4);
        List<Integer> pages = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            pages.add(i);
        }
        return pages;
    }
}
