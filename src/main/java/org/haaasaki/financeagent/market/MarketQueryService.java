package org.haaasaki.financeagent.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.haaasaki.financeagent.config.MarketProperties;
import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.mapper.MarketSnapshotMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.MarketRow;
import org.haaasaki.financeagent.data.model.MarketSnapshot;
import org.springframework.stereotype.Service;

/**
 * 市场模块查询服务。
 *
 * <p>只读本地数据：所有行情与快照来自 SQLite，页面渲染不发起外部请求。
 * 快照缺失时字段保持 null，由模板展示为 {@code --}，不做零值填充。
 */
@Service
public class MarketQueryService {

    /** 指数概览取最近这段自然日内的日K，足以覆盖长假后取到两日数据。 */
    private static final int INDEX_LOOKBACK_DAYS = 45;

    private final InstrumentMapper instrumentMapper;
    private final DailyBarMapper dailyBarMapper;
    private final MarketSnapshotMapper marketSnapshotMapper;
    private final MarketProperties marketProperties;

    public MarketQueryService(InstrumentMapper instrumentMapper,
                              DailyBarMapper dailyBarMapper,
                              MarketSnapshotMapper marketSnapshotMapper,
                              MarketProperties marketProperties) {
        this.instrumentMapper = instrumentMapper;
        this.dailyBarMapper = dailyBarMapper;
        this.marketSnapshotMapper = marketSnapshotMapper;
        this.marketProperties = marketProperties;
    }

    /** 市场列表分页查询。 */
    public MarketListResult list(MarketListQuery query) {
        List<MarketRow> rows = marketSnapshotMapper.findPage(
                query.keyword(),
                query.board(),
                query.instrumentType(),
                query.sort().column(),
                query.desc() ? "DESC" : "ASC",
                query.offset(),
                query.size());
        long total = marketSnapshotMapper.countRows(
                query.keyword(), query.board(), query.instrumentType());
        return new MarketListResult(rows, total, query);
    }

    /** 个股详情；标的字典中不存在时返回空。 */
    public Optional<StockDetail> findStockDetail(String instrumentId, KlineRange range) {
        return instrumentMapper.findById(instrumentId)
                .map(instrument -> {
                    LocalDate today = LocalDate.now();
                    List<DailyBar> bars =
                            dailyBarMapper.findRange(instrumentId, range.startDate(today), today);
                    MarketSnapshot snapshot = marketSnapshotMapper.findById(instrumentId);
                    return new StockDetail(instrument, snapshot, range, bars, buildChart(bars));
                });
    }

    /** 快照新鲜度摘要。 */
    public MarketSnapshotSummary snapshotSummary() {
        return new MarketSnapshotSummary(
                marketSnapshotMapper.count(), marketSnapshotMapper.findMaxFetchedAt());
    }

    /** 按配置顺序返回指数概览；数据缺失时数值为 null，卡片仍展示名称。 */
    public List<IndexQuote> indexQuotes() {
        List<MarketProperties.IndexDef> indexes = marketProperties.indexes();
        if (indexes == null || indexes.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        List<IndexQuote> quotes = new ArrayList<>(indexes.size());
        for (MarketProperties.IndexDef def : indexes) {
            List<DailyBar> bars = dailyBarMapper.findRange(
                    def.id(), today.minusDays(INDEX_LOOKBACK_DAYS), today);
            quotes.add(toIndexQuote(def, bars));
        }
        return quotes;
    }

    private IndexQuote toIndexQuote(MarketProperties.IndexDef def, List<DailyBar> bars) {
        if (bars.isEmpty()) {
            return new IndexQuote(def.id(), def.name(), null, null, null);
        }
        DailyBar latest = bars.get(bars.size() - 1);
        if (bars.size() < 2) {
            return new IndexQuote(def.id(), def.name(), latest.close(), null, null);
        }
        double previousClose = bars.get(bars.size() - 2).close();
        Double change = latest.close() - previousClose;
        Double pctChg = previousClose == 0.0 ? null : change / previousClose;
        return new IndexQuote(def.id(), def.name(), latest.close(), change, pctChg);
    }

    private KlineChartData buildChart(List<DailyBar> bars) {
        List<String> dates = new ArrayList<>(bars.size());
        List<List<Double>> candles = new ArrayList<>(bars.size());
        List<Long> volumes = new ArrayList<>(bars.size());
        for (DailyBar bar : bars) {
            dates.add(bar.tradeDate().toString());
            // ECharts candlestick 顺序：[open, close, low, high]
            candles.add(List.of(bar.open(), bar.close(), bar.low(), bar.high()));
            volumes.add(bar.volume());
        }
        return new KlineChartData(dates, candles, volumes);
    }
}
