-- V2 市场模块：全市场最新快照
-- 契约来源：docs/work-items/WI-002-市场模块.md 第 2.1 节
-- 单位约定：价格=元、成交量=股、百分比=小数、市值=元
-- 语义：每个标的保留最新一条；fetched_at 即该行数据的 available_at（抓取时刻）

CREATE TABLE IF NOT EXISTS market_snapshot (
    instrument_id TEXT PRIMARY KEY,
    last_price    REAL,
    pct_chg       REAL,
    volume        INTEGER,
    amount        REAL,
    turnover_rate REAL,
    pe_ttm        REAL,
    pb            REAL,
    total_mv      REAL,
    circ_mv       REAL,
    source        TEXT NOT NULL,
    fetched_at    TEXT NOT NULL,
    data_status   TEXT NOT NULL DEFAULT 'ACCEPTED',
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);
