package org.haaasaki.financeagent.backtest.engine;

/**
 * 调仓频率：信号取每个周期的最后一个交易日，成交在其后第一个交易日开盘。
 */
public enum RebalanceFrequency {

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
