-- V7 因子表 v2：量价与位置因子（WI-012）
-- 口径与缺失语义见 docs/work-items/WI-012-因子扩充v2.md 第 2.2 节
-- 新增列全部可空；存量行为 NULL，由 --factor.init 全量回填补齐
-- 注：SQLite ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS；如迁移中途失败，
--     重跑前需按 WI-012 §4 用 ALTER TABLE DROP COLUMN 清掉已添加的列

ALTER TABLE factor_daily ADD COLUMN volume_ratio_5 REAL;
ALTER TABLE factor_daily ADD COLUMN volume_ratio_20 REAL;
ALTER TABLE factor_daily ADD COLUMN vol_chg_20 REAL;
ALTER TABLE factor_daily ADD COLUMN atr_20 REAL;
ALTER TABLE factor_daily ADD COLUMN amplitude_20 REAL;
ALTER TABLE factor_daily ADD COLUMN downside_vol_20 REAL;
ALTER TABLE factor_daily ADD COLUMN pct_from_52w_low REAL;
ALTER TABLE factor_daily ADD COLUMN new_high_60 REAL;
ALTER TABLE factor_daily ADD COLUMN new_high_120 REAL;
