package org.haaasaki.financeagent.data.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Mapper;
import org.haaasaki.financeagent.data.model.SyncLogEntry;

/**
 * 本地数据状态统计 Mapper（只读）。
 *
 * <p>SQL 见 {@code resources/mapper/DataStatisticsMapper.xml}。
 * 仅服务于首页与启动摘要，不参与业务查询。
 */
@Mapper
public interface DataStatisticsMapper {

    long countInstruments();

    long countInstrumentsByType(String instrumentType);

    long countDailyBars();

    long countCoveredInstruments();

    LocalDate minTradeDate();

    LocalDate maxTradeDate();

    SyncLogEntry findLastSync();
}
