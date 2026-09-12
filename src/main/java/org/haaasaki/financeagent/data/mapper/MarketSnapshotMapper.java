package org.haaasaki.financeagent.data.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.MarketRow;
import org.haaasaki.financeagent.data.model.MarketSnapshot;

/**
 * 市场快照 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/MarketSnapshotMapper.xml}。
 * 快照每个标的仅保留最新一条，写入使用 upsert 保证同步任务幂等。
 *
 * <p>{@link #findPage} 的排序字段由调用方从白名单枚举取值后传入，
 * 绝不接受用户原始字符串。
 */
@Mapper
public interface MarketSnapshotMapper {

    int upsertBatch(@Param("list") List<MarketSnapshot> snapshots);

    /** 指定抓取时刻落库的行数，供同步条数校验使用。 */
    long countByFetchedAt(@Param("fetchedAt") String fetchedAt);

    long count();

    /** 全表最新抓取时刻（ISO 本地时间），空表返回 null。 */
    String findMaxFetchedAt();

    /** 单个标的的最新快照，不存在返回 null。 */
    MarketSnapshot findById(@Param("instrumentId") String instrumentId);

    /**
     * 市场列表分页查询（股票 + ETF，LEFT JOIN 快照，无快照的标的价格列返回 null）。
     *
     * @param keyword        代码或名称模糊匹配，可为 null
     * @param board          板块过滤，可为 null
     * @param instrumentType 标的类型过滤，可为 null
     * @param sortColumn     排序列（含表别名，来自白名单枚举）
     * @param sortDirection  {@code ASC} / {@code DESC}
     */
    List<MarketRow> findPage(@Param("keyword") String keyword,
                             @Param("board") String board,
                             @Param("instrumentType") String instrumentType,
                             @Param("sortColumn") String sortColumn,
                             @Param("sortDirection") String sortDirection,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    long countRows(@Param("keyword") String keyword,
                   @Param("board") String board,
                   @Param("instrumentType") String instrumentType);
}
