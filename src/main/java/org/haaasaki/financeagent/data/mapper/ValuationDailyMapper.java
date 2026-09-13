package org.haaasaki.financeagent.data.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.ValuationDaily;

/**
 * 历史估值 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/ValuationDailyMapper.xml}。
 * 写入用主键 upsert 保证幂等；无数据保持缺失，不填 0。
 */
@Mapper
public interface ValuationDailyMapper {

    int upsertBatch(@Param("list") List<ValuationDaily> rows);

    /** 自指定交易日（含）以来有估值记录的标的，用于增量时识别缺失/落后标的。 */
    List<String> findInstrumentIdsWithRowSince(@Param("since") LocalDate since);

    /** 全库最早估值日期，回测区间校验用。 */
    Optional<LocalDate> findGlobalMinTradeDate();

    /** 全库最新估值日期，增量更新用。 */
    Optional<LocalDate> findGlobalMaxTradeDate();

    long count();

    long countDistinctInstruments();
}
