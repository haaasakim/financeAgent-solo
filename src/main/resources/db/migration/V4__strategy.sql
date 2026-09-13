-- V4 选股模块：策略表
-- 契约来源：docs/work-items/WI-004-选股模块.md 第 2.1 节
-- dsl 为条件树 JSON；同名保存视为覆盖更新（服务端 upsert）

CREATE TABLE IF NOT EXISTS strategy (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    dsl         TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_strategy_updated ON strategy (updated_at);
