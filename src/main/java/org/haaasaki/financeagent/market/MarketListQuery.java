package org.haaasaki.financeagent.market;

/**
 * 市场列表查询条件。
 *
 * <p>分页约定：{@code page} 从 1 开始，{@code size} 默认 20、上限 200（见 docs/BACKEND_GUIDE.md）。
 *
 * @param keyword        代码或名称模糊匹配，空白视为不过滤
 * @param board          板块过滤（MAIN / GEM / STAR / BSE / ETF），空白视为不过滤
 * @param instrumentType 标的类型过滤（STOCK / ETF），空白视为不过滤
 * @param sort           排序字段白名单
 * @param desc           是否降序
 * @param page           页码，从 1 开始
 * @param size           每页条数
 */
public record MarketListQuery(
        String keyword,
        String board,
        String instrumentType,
        MarketSortField sort,
        boolean desc,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    public MarketListQuery {
        keyword = blankToNull(keyword);
        board = blankToNull(board);
        instrumentType = blankToNull(instrumentType);
        sort = sort == null ? MarketSortField.CODE : sort;
        page = Math.max(1, page);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /** 从原始请求参数构造，未知排序键回退默认值。 */
    public static MarketListQuery of(String keyword, String board, String instrumentType,
                                     String sortKey, String direction, int page, int size) {
        return new MarketListQuery(
                keyword,
                board,
                instrumentType,
                MarketSortField.fromKey(sortKey),
                "desc".equalsIgnoreCase(direction),
                page,
                size);
    }

    public int offset() {
        return (page - 1) * size;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
