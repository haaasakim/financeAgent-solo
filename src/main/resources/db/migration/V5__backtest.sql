-- 回测运行记录（同时充当异步任务记录）
CREATE TABLE IF NOT EXISTS backtest_task (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id       INTEGER,
    strategy_name     TEXT,
    strategy_hash     TEXT    NOT NULL,
    engine_version    TEXT    NOT NULL,
    params_json       TEXT    NOT NULL,
    status            TEXT    NOT NULL,
    progress_done     INTEGER NOT NULL DEFAULT 0,
    progress_total    INTEGER NOT NULL DEFAULT 0,
    message           TEXT,
    data_through      TEXT,
    benchmark_id      TEXT,
    initial_capital   REAL,
    final_nav         REAL,
    cumulative_return REAL,
    annual_return     REAL,
    max_drawdown      REAL,
    sharpe            REAL,
    win_rate          REAL,
    turnover          REAL,
    created_at        TEXT    NOT NULL,
    started_at        TEXT,
    finished_at       TEXT
);
CREATE INDEX IF NOT EXISTS idx_backtest_task_created ON backtest_task (created_at);

-- 日度净值序列：每日收盘盯市，首日基准归一为初始资金
CREATE TABLE IF NOT EXISTS backtest_daily (
    task_id       INTEGER NOT NULL,
    trade_date    TEXT    NOT NULL,
    nav           REAL,
    cash          REAL,
    market_value  REAL,
    benchmark_nav REAL,
    drawdown      REAL,
    created_at    TEXT    NOT NULL,
    PRIMARY KEY (task_id, trade_date)
);
CREATE INDEX IF NOT EXISTS idx_backtest_daily_task ON backtest_daily (task_id, trade_date);

-- 订单流水：未成交如实记录为 EXPIRED，不含部分成交
CREATE TABLE IF NOT EXISTS backtest_trade (
    task_id       INTEGER NOT NULL,
    order_no      INTEGER NOT NULL,
    instrument_id TEXT    NOT NULL,
    code          TEXT,
    name          TEXT,
    side          TEXT    NOT NULL,
    status        TEXT    NOT NULL,
    signal_date   TEXT,
    order_date    TEXT,
    filled_date   TEXT,
    price         REAL,
    shares        INTEGER,
    amount        REAL,
    fee           REAL,
    pnl           REAL,
    reason        TEXT,
    created_at    TEXT    NOT NULL,
    PRIMARY KEY (task_id, order_no)
);
CREATE INDEX IF NOT EXISTS idx_backtest_trade_task ON backtest_trade (task_id, order_no);
