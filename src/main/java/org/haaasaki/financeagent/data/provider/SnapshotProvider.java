package org.haaasaki.financeagent.data.provider;

import java.util.List;

import org.haaasaki.financeagent.data.model.InstrumentSnapshot;

/**
 * 全市场标的快照数据源接口。
 *
 * <p>用于获取标的清单（代码 + 名称）与腾讯日K不提供的估值字段（PE / PB / 市值 / 换手率）。
 *
 * <p>实现约定：
 * <ul>
 *   <li>返回全量结果（内部自行翻页），已换算到本站契约单位（市值=元、换手率=小数）</li>
 *   <li>股票与 ETF 分两个方法获取，因为新浪两者位于不同节点（{@code hs_a} / {@code etf_hq_fund}）</li>
 * </ul>
 */
public interface SnapshotProvider {

    /** 数据源标识。 */
    String name();

    /** 全部 A 股（含沪深主板、创业板、科创板、北交所）快照。 */
    List<InstrumentSnapshot> fetchStocks();

    /** 全部场内 ETF 快照。 */
    List<InstrumentSnapshot> fetchEtfs();
}
