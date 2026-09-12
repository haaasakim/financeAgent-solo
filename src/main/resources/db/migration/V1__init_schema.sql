-- V1 初始化表结构
-- 契约来源：docs/work-items/WI-001-数据层与全市场同步.md 第 2.1 节
-- 单位约定：价格=元、成交量=股、百分比=小数（0.0125 = 1.25%）

-- 标的字典
CREATE TABLE IF NOT EXISTS instrument (
    instrument_id   TEXT PRIMARY KEY,
    instrument_type TEXT NOT NULL,
    exchange        TEXT NOT NULL,
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    board           TEXT,
    currency        TEXT NOT NULL DEFAULT 'CNY',
    timezone        TEXT NOT NULL DEFAULT 'Asia/Shanghai',
    listed_at       TEXT,
    delisted_at     TEXT,
    status          TEXT NOT NULL DEFAULT 'LISTED',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_instrument_type ON instrument (instrument_type);
CREATE INDEX IF NOT EXISTS idx_instrument_board ON instrument (board);

-- 日K行情（前复权）
CREATE TABLE IF NOT EXISTS daily_bar (
    instrument_id  TEXT    NOT NULL,
    trade_date     TEXT    NOT NULL,
    open           REAL    NOT NULL,
    high           REAL    NOT NULL,
    low            REAL    NOT NULL,
    close          REAL    NOT NULL,
    pre_close      REAL,
    volume         INTEGER NOT NULL,
    amount         REAL,
    pct_chg        REAL,
    turnover_rate  REAL,
    adj_type       TEXT    NOT NULL DEFAULT 'QFQ',
    source         TEXT    NOT NULL,
    fetched_at     TEXT    NOT NULL,
    data_status    TEXT    NOT NULL DEFAULT 'ACCEPTED',
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL,
    PRIMARY KEY (instrument_id, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_daily_bar_date ON daily_bar (trade_date);

-- 交易日历
CREATE TABLE IF NOT EXISTS trading_calendar (
    trade_date TEXT PRIMARY KEY,
    is_open    INTEGER NOT NULL,
    note       TEXT,
    created_at TEXT NOT NULL
);

-- 同步审计
CREATE TABLE IF NOT EXISTS sync_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task_name     TEXT    NOT NULL,
    provider      TEXT,
    status        TEXT    NOT NULL,
    total_count   INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    fail_count    INTEGER NOT NULL DEFAULT 0,
    message       TEXT,
    started_at    TEXT    NOT NULL,
    finished_at   TEXT
);
CREATE INDEX IF NOT EXISTS idx_sync_log_started ON sync_log (started_at);

-- 异步任务
CREATE TABLE IF NOT EXISTS task (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    task_type      TEXT    NOT NULL,
    params         TEXT,
    status         TEXT    NOT NULL,
    progress_done  INTEGER NOT NULL DEFAULT 0,
    progress_total INTEGER NOT NULL DEFAULT 0,
    message        TEXT,
    created_at     TEXT    NOT NULL,
    started_at     TEXT,
    finished_at    TEXT
);
CREATE INDEX IF NOT EXISTS idx_task_status ON task (status);
