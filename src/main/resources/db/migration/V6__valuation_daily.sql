-- 历史估值日序列：逐日 PE-TTM / PB-MRQ，供估值因子回测使用
CREATE TABLE IF NOT EXISTS valuation_daily (
    instrument_id TEXT NOT NULL,
    trade_date    TEXT NOT NULL,
    pe_ttm        REAL,
    pb_mrq        REAL,
    source        TEXT NOT NULL,
    fetched_at    TEXT NOT NULL,
    data_status   TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    PRIMARY KEY (instrument_id, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_valuation_daily_date ON valuation_daily (trade_date);
