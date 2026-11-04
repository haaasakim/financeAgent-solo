package org.haaasaki.financeagent.data.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.DailyClose;
import org.haaasaki.financeagent.data.model.InstrumentBarStats;
import org.haaasaki.financeagent.data.model.InstrumentFirstBar;

/**
 * 日K行情 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/DailyBarMapper.xml}。
 * upsert 是关键：它同时承担两个职责——同步的幂等性，以及除权重拉时对历史价格的覆写。
 */
@Mapper
public interface DailyBarMapper {

    int upsertBatch(@Param("list") List<DailyBar> bars);

    List<DailyBar> findRange(@Param("instrumentId") String instrumentId,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to);

    /**
     * 最近 {@code limit} 根日K，按交易日升序。
     *
     * <p>供因子增量重算切片使用：需要一段「输出窗口 + 最长回看」的历史，
     * 且必须保证升序（因子函数依赖序列顺序）。
     */
    List<DailyBar> findRecent(@Param("instrumentId") String instrumentId,
                              @Param("limit") int limit);

    /** 只取日期与收盘价，供除权检测比对使用。 */
    List<DailyClose> findCloseList(@Param("instrumentId") String instrumentId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    /**
     * 除权检测用的便利方法：把投影结果转成「交易日 → 收盘价」。
     *
     * <p>放在 Mapper 上而非各处调用方，避免同一段 stream 收集在 Service 与测试里重复三遍。
     */
    default Map<LocalDate, Double> findCloseMap(String instrumentId, LocalDate from, LocalDate to) {
        return findCloseList(instrumentId, from, to).stream()
                .collect(Collectors.toMap(DailyClose::tradeDate, DailyClose::close, (a, b) -> b));
    }

    Optional<LocalDate> findMaxTradeDate(@Param("instrumentId") String instrumentId);

    /** 全库最大交易日，回测运行时的数据快照标识。 */
    Optional<LocalDate> findGlobalMaxTradeDate();

    /** 某标的的全部交易日，升序。回测用它回填交易日历。 */
    List<LocalDate> findTradeDates(@Param("instrumentId") String instrumentId);

    /** 全库出现过的交易日，升序；用于覆盖审计的“应有交易日”分母。 */
    List<LocalDate> findAllTradeDates();

    /** 各标的的日K条数与日期范围；用于覆盖审计。 */
    List<InstrumentBarStats> findInstrumentBarStats();

    /** 全部标的的首根日K日期，供回测排除上市不足 5 个交易日的标的。 */
    List<InstrumentFirstBar> findFirstTradeDates();

    Optional<LocalDate> findMinTradeDate(@Param("instrumentId") String instrumentId);

    long countByInstrument(@Param("instrumentId") String instrumentId);

    long count();

    /** 删除某标的的全部日K。仅用于数据修复，正常同步不使用（重拉靠 upsert 覆写）。 */
    int deleteByInstrumentId(@Param("instrumentId") String instrumentId);

    /** 各标的的日K条数与日期范围，供统计使用。 */
    long countDistinctInstruments();
}
