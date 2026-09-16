#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量回测脚本：创建/更新策略 → 提交回测 → 轮询 → 输出绩效与回撤结果。

前置条件：
  - 应用已启动（默认 http://localhost:8080），且代码版本与数据库一致；
  - 本机可直接读取 SQLite 库（默认 <repo>/data/finance.db）用于任务状态与结果读取。

用法示例：
  python3 script/batch_backtest.py                      # 全量跑默认矩阵
  python3 script/batch_backtest.py --only 缩量超跌       # 只跑名称包含该子串的策略
  python3 script/batch_backtest.py --windows BEAR        # 只跑熊市区间
  python3 script/batch_backtest.py --freqs DAILY         # 只跑指定频率
  python3 script/batch_backtest.py --report              # 仅用已有状态文件出报表
  python3 script/batch_backtest.py --create-only         # 只创建/更新策略，不跑回测

结果文件（默认 target/batch-backtest/，git 忽略）：
  state.json    任务键 → 任务 id（断点续跑依据）
  results.json  本次全部任务的参数与绩效明细
  results.csv   与终端表格同内容的 CSV

断点续跑：同一（策略, 区间, 频率）已有 SUCCESS 任务时直接复用，不重复回测；
需要重跑用 --force。任务失败会记录到结果表并返回非零退出码。
"""
import argparse
import csv
import json
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------------------
# 策略定义（WI-011 显式评分：score=true 参与排序，score=false 仅过滤）
# ---------------------------------------------------------------------------


def cond(factor, op, value, score=None, weight=None, direction=None):
    item = {"factor": factor, "op": op, "value": value}
    if score is not None:
        item["score"] = score
    if weight is not None:
        item["weight"] = weight
    if direction is not None:
        item["direction"] = direction
    return item


STRATEGIES = [
    {
        "name": "候选-缩量超跌",
        "description": "超跌+缩量（抛压衰竭）+低下行波动；评分：深跌度 2 : 缩量 1",
        "dsl": {"all": [
            cond("pctFrom52wHigh", "lte", -30, True, 2, "lower"),
            cond("volChg20", "lte", 0, True, 1, "lower"),
            cond("downsideVol20", "lte", 3, False),
        ], "any": [], "not": []},
    },
    {
        "name": "候选-缩量超跌回稳",
        "description": "超跌+缩量+站回 MA60（底部右侧确认）；评分：深跌度 1 : 缩量 1",
        "dsl": {"all": [
            cond("pctFrom52wHigh", "lte", -30, True, 1, "lower"),
            cond("volChg20", "lte", 0, True, 1, "lower"),
            cond("downsideVol20", "lte", 2.5, False),
            cond("aboveMa60", "eq", 1),
        ], "any": [], "not": []},
    },
    {
        "name": "候选-深度乖离回归",
        "description": "偏离 MA60 ≤ -20% + RSI6 ≤ 35 超卖确认；评分：乖离深度 2 : RSI 低 1",
        "dsl": {"all": [
            cond("bias60", "lte", -20, True, 2, "lower"),
            cond("rsi6", "lte", 35, True, 1, "lower"),
        ], "any": [], "not": []},
    },
    {
        "name": "候选-温和放量新高",
        "description": "创 60 日新高+温和放量（1.2~3 倍）+不追高（乖离≤15%）；低频使用",
        "dsl": {"all": [
            cond("newHigh60", "eq", 1),
            cond("volumeRatio20", "gte", 1.2, True, 1, "higher"),
            cond("volumeRatio20", "lte", 3, False),
            cond("bias20", "lte", 15, False),
            cond("ma5GtMa10GtMa20", "eq", 1),
            cond("return60", "gt", 0, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "候选-趋势回踩",
        "description": "上升趋势（return20>0、多头排列）中买低乖离回踩点；评分：return60 1 : 低乖离 1",
        "dsl": {"all": [
            cond("return20", "gt", 0, False),
            cond("ma5GtMa10GtMa20", "eq", 1),
            cond("bias20", "lte", 5, True, 1, "lower"),
            cond("return60", "gt", 0, True, 1, "higher"),
            cond("downsideVol20", "lte", 3, False),
        ], "any": [], "not": []},
    },
]

WINDOWS = {
    "BULL": ("2024-09-01", "2026-09-01"),
    "BEAR": ("2018-01-02", "2023-12-31"),
}

# 每个策略的调仓频率（动量类策略按建议只用低频）
FREQUENCIES = {
    "候选-缩量超跌": ["DAILY", "WEEKLY"],
    "候选-缩量超跌回稳": ["DAILY", "WEEKLY"],
    "候选-深度乖离回归": ["DAILY", "WEEKLY"],
    "候选-温和放量新高": ["WEEKLY", "MONTHLY"],
    "候选-趋势回踩": ["DAILY", "WEEKLY"],
}

BACKTEST_PARAMS = {
    "topN": 5,
    "initialCapital": "1000000",
    "commissionRatePct": "0.03",
    "stampTaxPct": "0.05",
    "slippageBps": "5",
    "stopLossPct": 12,
    "timeStopDays": 0,
    "benchmarkId": "000300.SH",
}

LOCK = threading.Lock()
SUBMIT_LOCK = threading.Lock()


def log(message):
    with LOCK:
        print(message, flush=True)


# ---------------------------------------------------------------------------
# HTTP 与数据库访问
# ---------------------------------------------------------------------------


def post(base_url, path, data, retries=4):
    """表单 POST；对 5xx 重试（SQLite 并发写可能出现 BUSY_SNAPSHOT）。"""
    body = urllib.parse.urlencode(data).encode("utf-8")
    req = urllib.request.Request(base_url + path, data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    last = None
    for attempt in range(retries):
        try:
            with opener.open(req, timeout=60) as resp:
                return resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            last = exc
            time.sleep(2 + attempt * 2)
        except urllib.error.URLError as exc:
            last = exc
            time.sleep(2 + attempt * 2)
    raise RuntimeError("请求失败 %s：%s" % (path, last))


def db(db_path, sql, args=()):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True, timeout=30)
    try:
        con.row_factory = sqlite3.Row
        return con.execute(sql, args).fetchall()
    finally:
        con.close()


def ensure_strategy(base_url, db_path, name, description, dsl):
    """按名称 upsert 策略（保存接口同名覆盖），返回策略 id。"""
    dsl_json = json.dumps(dsl, ensure_ascii=False, separators=(",", ":"))
    post(base_url, "/fragments/screener/strategies",
         {"name": name, "description": description, "dsl": dsl_json})
    rows = db(db_path, "SELECT id, dsl FROM strategy WHERE name = ?", (name,))
    if not rows:
        raise RuntimeError("策略保存失败：%s（请检查服务端日志与版本）" % name)
    if rows[0]["dsl"] != dsl_json:
        raise RuntimeError("策略 %s 保存后的 DSL 与预期不一致" % name)
    return rows[0]["id"]


def submit_task(base_url, strategy_id, start, end, frequency):
    params = dict(BACKTEST_PARAMS)
    params.update({"strategyId": strategy_id, "startDate": start,
                   "endDate": end, "frequency": frequency})
    with SUBMIT_LOCK:  # 串行提交，规避并发建任务时的 SQLite 写碰撞
        html = post(base_url, "/fragments/backtest/run", params)
    match = re.search(r"/fragments/backtest/(\d+)/status", html)
    if not match:
        raise RuntimeError("未解析到任务 id；响应片段：" + html[:300])
    return int(match.group(1))


def wait_task(db_path, task_id, poll_seconds=5):
    while True:
        rows = db(db_path, "SELECT status, message FROM backtest_task WHERE id = ?", (task_id,))
        if not rows:
            raise RuntimeError("任务不存在：#%d" % task_id)
        if rows[0]["status"] in ("SUCCESS", "FAILED"):
            return rows[0]["status"], rows[0]["message"]
        time.sleep(poll_seconds)


# ---------------------------------------------------------------------------
# 运行矩阵与断点续跑
# ---------------------------------------------------------------------------


def load_state(out_dir):
    path = os.path.join(out_dir, "state.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    return {}


def save_state(out_dir, state):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "state.json")
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(state, handle, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def build_jobs(args):
    jobs = []
    for strategy in STRATEGIES:
        if args.only and not any(key in strategy["name"] for key in args.only):
            continue
        for window in args.windows:
            start, end = WINDOWS[window]
            for frequency in FREQUENCIES.get(strategy["name"], ["DAILY"]):
                if args.freqs and frequency not in args.freqs:
                    continue
                jobs.append({
                    "key": "%s|%s|%s" % (strategy["name"], window, frequency),
                    "strategy": strategy["name"],
                    "window": window, "start": start, "end": end,
                    "frequency": frequency,
                })
    return jobs


def run_all(args):
    state = load_state(args.out)
    state_lock = threading.Lock()

    log("创建/更新策略…")
    ids = {}
    for strategy in STRATEGIES:
        ids[strategy["name"]] = ensure_strategy(
            args.base_url, args.db, strategy["name"], strategy["description"], strategy["dsl"])
        log("  策略 #%d %s" % (ids[strategy["name"]], strategy["name"]))
    if args.create_only:
        return 0

    jobs = build_jobs(args)
    log("共 %d 个回测任务（并发 %d，提交串行）" % (len(jobs), args.concurrency))

    def run_one(job):
        key = job["key"]
        if key in state and not args.force:
            status, _ = wait_task(args.db, state[key])
            if status == "SUCCESS":
                return key, state[key], "reuse"
        task_id = submit_task(args.base_url, ids[job["strategy"]],
                              job["start"], job["end"], job["frequency"])
        with state_lock:
            state[key] = task_id
            save_state(args.out, state)
        status, message = wait_task(args.db, task_id)
        return key, task_id, status if status == "SUCCESS" else "FAILED: %s" % message

    failed = 0
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(run_one, job) for job in jobs]
        for future in as_completed(futures):
            try:
                key, task_id, status = future.result()
                log("%-34s task#%-4d %s" % (key, task_id, status))
                if status.startswith("FAILED"):
                    failed += 1
            except Exception as exc:  # noqa: BLE001
                failed += 1
                log("异常：%s" % exc)
    save_state(args.out, state)
    report(args)
    return 1 if failed else 0


# ---------------------------------------------------------------------------
# 报表
# ---------------------------------------------------------------------------


def collect(args):
    state = load_state(args.out)
    rows = []
    for key, task_id in state.items():
        result = db(args.db, """
            SELECT t.id, t.strategy_name, t.status, t.cumulative_return, t.annual_return,
                   t.max_drawdown, t.sharpe, t.win_rate, t.turnover, t.final_nav,
                   json_extract(t.params_json, '$.frequency') AS frequency,
                   json_extract(t.params_json, '$.topN') AS top_n,
                   json_extract(t.params_json, '$.startDate') AS start_date,
                   json_extract(t.params_json, '$.endDate') AS end_date,
                   (SELECT COUNT(*) FROM backtest_trade tr
                     WHERE tr.task_id = t.id AND tr.status = 'FILLED') AS trades
            FROM backtest_task t WHERE t.id = ?""", (task_id,))
        if not result:
            continue
        row = dict(result[0])
        row["key"] = key
        rows.append(row)
    return rows


def pct(value):
    return "--" if value is None else "%.1f" % (value * 100)


def num(value, digits=2):
    return "--" if value is None else ("%%.%df" % digits) % value


def report(args):
    rows = collect(args)
    if not rows:
        log("没有可输出的结果（state.json 为空）")
        return
    rows.sort(key=lambda row: row["key"])
    print("")
    print("%-24s %-4s %-7s %8s %8s %8s %7s %7s %7s %8s %8s %6s" % (
        "策略", "区间", "频率", "累计%", "年化%", "回撤%", "卡玛", "夏普",
        "胜率%", "换手", "成交笔数", "状态"))
    for row in rows:
        annual, drawdown = row["annual_return"], row["max_drawdown"]
        calmar = "--" if not annual or not drawdown else "%.2f" % (annual / abs(drawdown))
        print("%-24s %-4s %-7s %8s %8s %8s %7s %7s %7s %8s %8d %6s" % (
            row["strategy_name"][:24], row["key"].split("|")[1], row["frequency"],
            pct(row["cumulative_return"]), pct(annual), pct(drawdown), calmar,
            num(row["sharpe"]), pct(row["win_rate"]), num(row["turnover"], 1),
            row["trades"], row["status"][:6]))

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "results.json"), "w", encoding="utf-8") as handle:
        json.dump(rows, handle, ensure_ascii=False, indent=2)
    with open(os.path.join(args.out, "results.csv"), "w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        columns = ["key", "strategy_name", "frequency", "start_date", "end_date", "top_n",
                   "status", "cumulative_return", "annual_return", "max_drawdown",
                   "sharpe", "win_rate", "turnover", "trades", "final_nav"]
        writer.writerow(columns)
        for row in rows:
            writer.writerow([row.get(column) for column in columns])
    print("\n已写出 %s/results.json 与 results.csv" % args.out)


def main():
    parser = argparse.ArgumentParser(description="创建策略并批量回测，输出绩效与回撤结果")
    parser.add_argument("--base-url", default="http://localhost:8080", help="应用地址")
    parser.add_argument("--db", default=os.path.join(ROOT, "data", "finance.db"), help="SQLite 库路径")
    parser.add_argument("--out", default=os.path.join(ROOT, "target", "batch-backtest"),
                        help="状态与结果输出目录")
    parser.add_argument("--only", default="", help="只跑名称包含任一子串的策略，逗号分隔")
    parser.add_argument("--windows", default="BULL,BEAR", help="区间名，逗号分隔（BULL/BEAR）")
    parser.add_argument("--freqs", default="", help="频率过滤，逗号分隔（DAILY/WEEKLY/MONTHLY）")
    parser.add_argument("--concurrency", type=int, default=3, help="回测并发数")
    parser.add_argument("--force", action="store_true", help="忽略已有成功任务，全部重跑")
    parser.add_argument("--create-only", action="store_true", help="只创建策略")
    parser.add_argument("--report", action="store_true", help="仅输出已有结果")
    args = parser.parse_args()

    args.only = [key.strip() for key in args.only.split(",") if key.strip()]
    args.windows = [key.strip().upper() for key in args.windows.split(",") if key.strip()]
    args.freqs = [key.strip().upper() for key in args.freqs.split(",") if key.strip()]
    for window in args.windows:
        if window not in WINDOWS:
            parser.error("未知区间：%s（可选 %s）" % (window, "/".join(WINDOWS)))

    if not os.path.exists(args.db):
        parser.error("数据库不存在：%s" % args.db)
    if args.report:
        report(args)
        return 0
    return run_all(args)


if __name__ == "__main__":
    sys.exit(main())
