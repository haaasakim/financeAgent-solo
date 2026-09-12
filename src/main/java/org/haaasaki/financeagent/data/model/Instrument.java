package org.haaasaki.financeagent.data.model;

import java.time.LocalDate;

/**
 * 标的档案（股票 / ETF / 指数）。
 *
 * <p>跨表关联必须使用 {@code instrumentId}，不得使用裸代码（见 docs/DATA_CONTRACT.md 第 1 节）。
 *
 * @param instrumentId   全局唯一标识，格式 {@code {code}.{exchange}}，例如 {@code 600000.SH}
 * @param instrumentType 标的类型：{@code STOCK} / {@code ETF} / {@code INDEX}
 * @param exchange       交易所：{@code SH} / {@code SZ} / {@code BJ}
 * @param code           裸代码，如 {@code 600000}
 * @param name           证券简称
 * @param board          板块：{@code MAIN} / {@code GEM} / {@code STAR} / {@code BSE} / {@code ETF} / {@code INDEX}，
 *                       决定回测撮合时的涨跌停幅度
 * @param currency       计价币种，A 股固定 {@code CNY}
 * @param timezone       交易日所属时区，A 股固定 {@code Asia/Shanghai}
 * @param listedAt       上市日期。M1 由日K最早交易日近似回填，对老股会晚于真实上市日
 * @param delistedAt     退市日期。M1 不采集退市股，恒为 null
 * @param status         标的状态：{@code LISTED} / {@code SUSPENDED} / {@code DELISTED}。
 *                       读取时必须按历史日期判断，不得用当前状态代表历史
 */
public record Instrument(
        String instrumentId,
        String instrumentType,
        String exchange,
        String code,
        String name,
        String board,
        String currency,
        String timezone,
        LocalDate listedAt,
        LocalDate delistedAt,
        String status) {

    public static final String TYPE_STOCK = "STOCK";
    public static final String TYPE_ETF = "ETF";
    public static final String TYPE_INDEX = "INDEX";

    public static final String STATUS_LISTED = "LISTED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_DELISTED = "DELISTED";

    public boolean isStock() {
        return TYPE_STOCK.equals(instrumentType);
    }

    public boolean isEtf() {
        return TYPE_ETF.equals(instrumentType);
    }
}
