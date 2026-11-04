package org.haaasaki.financeagent.data.support;

import org.haaasaki.financeagent.data.mapper.DataStatisticsMapper;
import org.haaasaki.financeagent.data.model.DataStatistics;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.SyncLogEntry;
import org.springframework.stereotype.Service;

/**
 * 本地数据状态统计组装。
 *
 * <p>{@link DataStatistics} 跨多个表，无法用单条 SQL 取出，
 * 因此用一个轻量 Service 负责组装，让 Mapper 只做 SQL、调用方只拿结果。
 */
@Service
public class DataStatisticsService {

    private final DataStatisticsMapper mapper;

    public DataStatisticsService(DataStatisticsMapper mapper) {
        this.mapper = mapper;
    }

    public DataStatistics collect() {
        SyncLogEntry last = mapper.findLastSync();
        return new DataStatistics(
                mapper.countInstruments(),
                mapper.countInstrumentsByType(Instrument.TYPE_STOCK),
                mapper.countInstrumentsByType(Instrument.TYPE_ETF),
                mapper.countDailyBars(),
                mapper.countCoveredInstruments(),
                mapper.countLatestDateCoveredInstruments(),
                mapper.minTradeDate(),
                mapper.maxTradeDate(),
                last == null ? null : last.taskName(),
                last == null ? null : last.status(),
                last == null ? null : last.finishedAt());
    }
}
