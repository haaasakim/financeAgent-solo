package org.haaasaki.financeagent.backtest.engine;

/**
 * 调仓频率：信号取每个周期的最后一个交易日，成交在其后第一个交易日开盘；
 * 每日频率即每个交易日收盘都作为信号日（t+1 开盘成交，仍无未来函数）。
 */
public enum RebalanceFrequency {

    DAILY("每日"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    QUARTERLY("每季");

    private final String label;

    RebalanceFrequency(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static RebalanceFrequency from(String value) {
        if (value == null || value.isBlank()) {
            return MONTHLY;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
