-- V3 因子表：技术指标宽表
-- 契约来源：docs/work-items/WI-003-指标计算与因子表.md 第 2 节
-- 口径：全部基于前复权收盘价，按标的自身 bar 序列计算（不对齐日历）
-- 缺失语义：回看不足的列为 NULL，禁止填 0

CREATE TABLE IF NOT EXISTS factor_daily (
    instrument_id     TEXT NOT NULL,
    trade_date        TEXT NOT NULL,
    ma5               REAL,
    ma10              REAL,
    ma20              REAL,
    ma60              REAL,
    macd_dif          REAL,
    macd_dea          REAL,
    macd_hist         REAL,
    rsi6              REAL,
    rsi12             REAL,
    rsi24             REAL,
    return_20         REAL,
    return_60         REAL,
    volatility_20     REAL,
    pct_from_52w_high REAL,
    data_status       TEXT NOT NULL DEFAULT 'ACCEPTED',
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    PRIMARY KEY (instrument_id, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_factor_daily_date ON factor_daily (trade_date);
