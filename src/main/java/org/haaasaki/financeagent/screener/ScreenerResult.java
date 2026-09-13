package org.haaasaki.financeagent.screener;

import java.util.List;

import org.haaasaki.financeagent.data.model.ScreenerRow;

/**
 * 选股结果分页。
 *
 * @param rows  当前页结果
 * @param total 满足条件的总数
 * @param page  当前页，从 1 开始
 * @param size  每页条数（固定 50）
 */
public record ScreenerResult(
        List<ScreenerRow> rows,
        long total,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 50;

    /** 匹配总数上限：超过时仅展示计数，不下发明细（WI-004 §2.4）。 */
    public static final long MAX_MATCHES = 5_000;

    public boolean exceeded() {
        return total > MAX_MATCHES;
    }

    public int totalPages() {
        return total == 0 ? 1 : (int) ((total + size - 1) / size);
    }

    public boolean hasPrev() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages();
    }

    public long from() {
        return total == 0 ? 0 : (long) (page - 1) * size + 1;
    }

    public long to() {
        return Math.min(total, (long) page * size);
    }
}
