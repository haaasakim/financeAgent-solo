package org.haaasaki.financeagent.backtest;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.TradingCalendarMapper;
import org.springframework.stereotype.Service;

/**
 * 交易日历服务：用基准指数日K幂等回填 {@code trading_calendar}（只补不删），
 * 回测按日历读交易日，不按自然周推导（见 docs/DATA_CONTRACT.md 第 4 节）。
 */
@Service
public class TradingCalendarService {

    private static final int BATCH = 500;

    private final TradingCalendarMapper tradingCalendarMapper;
    private final DailyBarMapper dailyBarMapper;

    public TradingCalendarService(TradingCalendarMapper tradingCalendarMapper,
                                  DailyBarMapper dailyBarMapper) {
        this.tradingCalendarMapper = tradingCalendarMapper;
        this.dailyBarMapper = dailyBarMapper;
    }

    /** 回填基准的全部交易日；基准无数据抛用户可见异常。 */
    public void ensureFromBenchmark(String benchmarkId) {
        List<LocalDate> dates = dailyBarMapper.findTradeDates(benchmarkId);
        if (dates.isEmpty()) {
            throw new BusinessException("基准「" + benchmarkId + "」没有日K数据，无法回填交易日历");
        }
        for (int i = 0; i < dates.size(); i += BATCH) {
            tradingCalendarMapper.insertOpenDays(dates.subList(i, Math.min(dates.size(), i + BATCH)));
        }
    }

    public List<LocalDate> openDates(LocalDate from, LocalDate to) {
        return tradingCalendarMapper.findOpenDates(from, to);
    }
}
