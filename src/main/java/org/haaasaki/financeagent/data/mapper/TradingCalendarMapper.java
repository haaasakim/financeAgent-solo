package org.haaasaki.financeagent.data.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易日历 Mapper。
 *
 * <p>日历由基准指数日K幂等回填（只补不删）；回测只读取 {@code is_open = 1} 的日期，
 * 不按自然周推导（见 docs/DATA_CONTRACT.md 第 4 节）。
 */
@Mapper
public interface TradingCalendarMapper {

    int insertOpenDays(@Param("list") List<LocalDate> dates);

    List<LocalDate> findOpenDates(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long count();
}
