package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 日K行情（前复权）。
 *
 * <p>缺失语义：无数据用 {@code null}，<b>禁止用 0 代替</b>——0 是合法的成交量，含义不同。
 *
 * @param instrumentId  统一标的标识，如 {@code 600000.SH}
 * @param tradeDate     交易日，无时区日期
 * @param open          开盘价，元
 * @param high          最高价，元
 * @param low           最低价，元
 * @param close         收盘价，元
 * @param preClose      前收盘价，元；当日为首条数据时为 null（缺失语义，不填 0）
 * @param volume        成交量，<b>股</b>。腾讯接口返回「手」，Provider 负责 ×100 换算
 * @param amount        成交额，元。腾讯日K不提供，待新浪快照补全，当前恒为 null
 * @param pctChg        涨跌幅，小数（0.0125 = 1.25%），由前收盘计算；首条数据为 null
 * @param turnoverRate  换手率，小数。腾讯日K不提供，当前恒为 null
 * @param adjType       复权口径：{@code QFQ} / {@code HFQ} / {@code NONE}。M1 全量前复权
 * @param source        数据来源标识，如 {@code TENCENT_FQKLINE}
 */
public record DailyBar(
        String instrumentId,
        LocalDate tradeDate,
        double open,
        double high,
        double low,
        double close,
        Double preClose,
        long volume,
        Double amount,
        Double pctChg,
        Double turnoverRate,
        String adjType,
        String source) {

    public static final String ADJ_QFQ = "QFQ";
    public static final String ADJ_HFQ = "HFQ";
    public static final String ADJ_NONE = "NONE";

    /** 涨跌停判定用：当日四个价格完全相等且触及涨跌停价时视为「一字板」。 */
    public boolean isFlatPrice() {
        return open == high && high == low && low == close;
    }
}
