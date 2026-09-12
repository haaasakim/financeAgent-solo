package org.haaasaki.financeagent.data.model;

/**
 * 全市场标的快照，来自新浪 {@code Market_Center.getHQNodeData}。
 *
 * <p>用途：获取标的清单（代码 + 名称），并补全腾讯日K不提供的估值字段。
 *
 * <p>单位契约——注意新浪原始返回的单位与本站契约不同，Provider 必须完成换算：
 * <ul>
 *   <li>{@code mktcap} / {@code nmc} 原始单位为<b>万元</b>，此处已统一为<b>元</b></li>
 *   <li>{@code turnoverratio} 原始为百分数数值（0.94 表示 0.94%），此处已统一为<b>小数</b></li>
 * </ul>
 *
 * @param symbol       交易所前缀代码，如 {@code sh600000}（小写）
 * @param code         裸代码，如 {@code 600000}
 * @param name         证券简称
 * @param peTtm        市盈率 TTM，倍。亏损或未提供时为 null（不填 0）
 * @param pb           市净率，倍。未提供时为 null
 * @param totalMv      总市值，元（新浪原始单位是万元，Provider 已 ×10000）
 * @param circMv       流通市值，元（同上，已换算）
 * @param turnoverRate 换手率，小数（新浪原始 0.94 表示 0.94%，Provider 已 ÷100）
 */
public record InstrumentSnapshot(
        String symbol,
        String code,
        String name,
        Double peTtm,
        Double pb,
        Double totalMv,
        Double circMv,
        Double turnoverRate) {
}
