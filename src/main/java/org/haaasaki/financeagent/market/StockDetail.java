package org.haaasaki.financeagent.market;

import java.util.List;

import org.haaasaki.financeagent.common.DisplayFormat;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.Instrument;
import org.haaasaki.financeagent.data.model.MarketSnapshot;

/**
 * 个股详情视图模型。
 *
 * @param instrument 标的档案
 * @param snapshot   最新快照，可能为 null（尚未同步快照）
 * @param range      当前 K 线区间
 * @param bars       区间内前复权日K，按交易日升序，可能为空
 * @param chart      ECharts 数据
 */
public record StockDetail(
        Instrument instrument,
        MarketSnapshot snapshot,
        KlineRange range,
        List<DailyBar> bars,
        KlineChartData chart) {

    /** 区间内最近一根日K；无数据时为 null。 */
    public DailyBar latestBar() {
        return bars.isEmpty() ? null : bars.get(bars.size() - 1);
    }

    /** 最新价：优先快照，缺快照时回退到最近日K收盘；均无则为 null。 */
    public Double latestPrice() {
        if (snapshot != null && snapshot.lastPrice() != null) {
            return snapshot.lastPrice();
        }
        DailyBar bar = latestBar();
        return bar == null ? null : bar.close();
    }

    /** 涨跌幅：优先快照，缺快照时回退到最近日K；均无则为 null。 */
    public Double latestPctChg() {
        if (snapshot != null && snapshot.pctChg() != null) {
            return snapshot.pctChg();
        }
        DailyBar bar = latestBar();
        return bar == null ? null : bar.pctChg();
    }

    /** 用于展示的数据日期标注。 */
    public String dataAsOf() {
        DailyBar bar = latestBar();
        String tradeDate = bar == null ? null : bar.tradeDate().toString();
        String fetchedAt = snapshot == null ? null : snapshot.fetchedAt();
        if (tradeDate == null && fetchedAt == null) {
            return null;
        }
        return (tradeDate == null ? "日K 无数据" : "日K 截至 " + tradeDate)
                + (fetchedAt == null ? "" : " · 快照抓取于 " + DisplayFormat.timestamp(fetchedAt));
    }
}
