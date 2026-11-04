#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量回测脚本：创建/更新策略 → 提交回测 → 轮询 → 输出绩效与回撤结果。

前置条件：
  - 应用已启动（默认 http://localhost:8080），且代码版本与数据库一致；
  - 本机可直接读取 SQLite 库（默认 <repo>/data/finance.db）用于任务状态与结果读取。

用法示例：
  python3 script/batch_backtest.py                      # 全量跑默认矩阵
  python3 script/batch_backtest.py --only 低估           # 只跑名称包含该子串的策略
  python3 script/batch_backtest.py --windows HISTORICAL  # 只跑 2018-01-02~2024-01-01
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


def cond(factor, op, value, score=None, weight=None, direction=None,
         band_low=None, band_high=None, band_low_tolerance=None, band_high_tolerance=None):
    item = {"factor": factor, "op": op, "value": value}
    if score is not None:
        item["score"] = score
    if weight is not None:
        item["weight"] = weight
    if direction is not None:
        item["direction"] = direction
    if band_low is not None:
        item["bandLow"] = band_low
    if band_high is not None:
        item["bandHigh"] = band_high
    if band_low_tolerance is not None:
        item["bandLowTolerance"] = band_low_tolerance
    if band_high_tolerance is not None:
        item["bandHighTolerance"] = band_high_tolerance
    return item


STRATEGIES = [
    {
        "name": "实验-趋势新高",
        "description": "股票、非 ST、上市满 250 日；60/120 日新高且中期为正，限制波动；覆盖趋势、基础、风险、量能因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250),
            cond("newHigh120", "eq", 1), cond("newHigh60", "eq", 1),
            cond("return60", "gt", 0), cond("volumeRatio5", "gte", 0.8),
            cond("volatility20", "lte", 8, True, 1, "lower"),
            cond("pctFrom52wHigh", "gte", -15, True, 2, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-新高回撤",
        "description": "新高后的有限回撤，不追逐短期过热；覆盖趋势、风险、量能因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("newHigh120", "eq", 1),
            cond("newHigh60", "eq", 1), cond("return60", "gt", 0),
            cond("return20", "lte", 12, True, 1, "lower"),
            cond("bias20", "lte", 8, True, 1, "lower"),
            cond("downsideVol20", "lte", 6, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-新高低波",
        "description": "120 日新高与低波动组合，过滤异常振幅；覆盖趋势、风险、量能因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("newHigh120", "eq", 1),
            cond("pctFrom52wHigh", "gte", -20, True, 2, "higher"),
            cond("volatility20", "lte", 6, True, 1, "lower"),
            cond("downsideVol20", "lte", 4, False),
            cond("amplitude20", "lte", 8, False),
            cond("volumeRatio20", "lte", 3, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-低估反转",
        "description": "正 PE/PB 的低估值、短线超卖与回撤组合；覆盖估值、趋势、风险因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("peTtm", "gt", 0),
            cond("peTtm", "lte", 25, True, 2, "lower"),
            cond("pb", "gt", 0), cond("pb", "lte", 3, True, 1, "lower"),
            cond("rsi6", "lte", 45, True, 1, "lower"),
            cond("bias20", "lte", 0, True, 1, "lower"),
            cond("downsideVol20", "lte", 6, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-低估低波",
        "description": "低估值与低波动组合，减少高波动价值陷阱；覆盖估值、风险、基础因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("peTtm", "gt", 0),
            cond("peTtm", "lte", 20, True, 2, "lower"),
            cond("pb", "gt", 0), cond("pb", "lte", 2.5, True, 1, "lower"),
            cond("volatility20", "lte", 6, True, 1, "lower"),
            cond("downsideVol20", "lte", 4, False),
            cond("amplitude20", "lte", 8, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-低估趋势",
        "description": "低估值叠加 MA20/MA60 上方趋势，避免纯粹接下落刀；覆盖估值、趋势、基础因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("peTtm", "gt", 0),
            cond("peTtm", "lte", 30, True, 2, "lower"),
            cond("pb", "gt", 0), cond("pb", "lte", 4, True, 1, "lower"),
            cond("aboveMa60", "eq", 1), cond("ma20GtMa60", "eq", 1),
            cond("return60", "gt", 0), cond("bias20", "lte", 10, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-超卖右侧",
        "description": "RSI/乖离超卖但仍在长期趋势上方，覆盖反转、趋势、风险因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("rsi6", "lte", 40, True, 1, "lower"),
            cond("bias20", "lte", -5, True, 2, "lower"),
            cond("aboveMa60", "eq", 1), cond("return60", "gt", 0),
            cond("pctFrom52wHigh", "lte", -5, False),
            cond("downsideVol20", "lte", 5, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-低波防守",
        "description": "低波动、低下行风险且中期不为负，覆盖风险、趋势、基础因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False), cond("return60", "gt", 0),
            cond("aboveMa60", "eq", 1),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-量能衰竭",
        "description": "缩量、短线超卖与回撤组合，等待抛压衰竭；覆盖量能、反转、风险因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("volChg20", "lte", 0, True, 1, "lower"),
            cond("volumeRatio5", "lte", 1.2, False), cond("rsi6", "lte", 45, True, 1, "lower"),
            cond("bias20", "lte", 0, True, 2, "lower"),
            cond("pctFrom52wHigh", "lte", -10, False),
            cond("downsideVol20", "lte", 6, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-量价突破",
        "description": "60 日新高配合温和放量，限制波动与过度追高；覆盖趋势、量能、风险因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("newHigh60", "eq", 1),
            cond("volumeRatio5", "gte", 1.2, True, 2, "band",
                 band_low=1.2, band_high=2.5, band_low_tolerance=1.0, band_high_tolerance=1.0),
            cond("volumeRatio5", "lte", 3, False), cond("volumeRatio20", "lte", 3, False),
            cond("return20", "gt", 0), cond("volatility20", "lte", 8, False),
            cond("pctFrom52wHigh", "gte", -10, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-MACD反转",
        "description": "MACD 柱翻正配合短线回撤与超卖，覆盖趋势、反转、量能因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("macdHist", "gte", 0, True, 1, "higher"),
            cond("rsi6", "lte", 50, True, 1, "lower"), cond("bias20", "lte", 0, True, 1, "lower"),
            cond("volumeRatio20", "lte", 2.5, False), cond("downsideVol20", "lte", 6, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-综合多因子",
        "description": "新高、低估值、低波动综合排序；覆盖基础、估值、趋势、量能、风险因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 1), cond("excludeSt", "eq", 1),
            cond("listedDays", "gte", 250), cond("newHigh60", "eq", 1),
            cond("peTtm", "gt", 0), cond("peTtm", "lte", 30, True, 2, "lower"),
            cond("pb", "gt", 0), cond("pb", "lte", 4, True, 1, "lower"),
            cond("volatility20", "lte", 8, True, 1, "lower"),
            cond("volumeRatio5", "gte", 0.8, False),
            cond("pctFrom52wHigh", "gte", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波",
        "description": "不限定股票/ETF，使用上市年限、低波动与低下行风险筛选；检验跨品种低波组合",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波-止损3",
        "description": "全市场低波策略的独立 3% 固定止损复核变体；手续费不变",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波-时间止损10",
        "description": "全市场低波策略的独立 10 个交易日未盈利退出变体；手续费不变",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场纯低波",
        "description": "不限定股票/ETF，只按低波动、低下行风险和低振幅排序；作为低波基线，减少额外择时条件",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波宽松",
        "description": "不限定股票/ETF，放宽波动阈值但保留低下行风险与距高点约束；测试低波策略容量",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 10, True, 2, "lower"),
            cond("downsideVol20", "lte", 6, True, 1, "lower"),
            cond("amplitude20", "lte", 12, False),
            cond("pctFrom52wHigh", "gte", -20, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波回撤",
        "description": "低波动池叠加距 52 周高点至少 5% 的回撤，并按回撤深度评分；基于分段因子研究复核反转方向",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 10, True, 2, "lower"),
            cond("downsideVol20", "lte", 6, True, 1, "lower"),
            cond("amplitude20", "lte", 12, False),
            cond("pctFrom52wHigh", "lte", -5, True, 2, "lower"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波区间",
        "description": "低波动池中只取距 52 周高点约 -20%~-5% 的中等回撤，使用区间评分减少极端值影响",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 10, True, 2, "lower"),
            cond("downsideVol20", "lte", 6, True, 1, "lower"),
            cond("amplitude20", "lte", 12, False),
            cond("pctFrom52wHigh", "gte", -30, False),
            cond("pctFrom52wHigh", "lte", -5, True, 2, "band",
                 band_low=-20, band_high=-5, band_low_tolerance=10, band_high_tolerance=5),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波双状态",
        "description": "低波动底层池中允许 120 日新高或中等回撤任一状态，通过截面评分在两类资产间切换",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 10, True, 2, "lower"),
            cond("downsideVol20", "lte", 6, True, 1, "lower"),
            cond("amplitude20", "lte", 12, False),
        ], "any": [
            cond("newHigh120", "eq", 1),
            cond("pctFrom52wHigh", "lte", -5, True, 2, "band",
                 band_low=-20, band_high=-5, band_low_tolerance=10, band_high_tolerance=5),
        ], "not": []},
    },
    {
        "name": "实验-全市场低波低RSI",
        "description": "不限定股票/ETF，低波动组合增加 RSI6 不过热过滤，减少高位债券与拥挤标的",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("rsi6", "lte", 70, False),
            cond("pctFrom52wHigh", "gte", -25, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-自适应低波",
        "description": "低波动底层池中允许新高、超卖或深度回撤任一信号；检验多状态因子组合",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 10, True, 2, "lower"),
            cond("downsideVol20", "lte", 6, True, 1, "lower"),
            cond("amplitude20", "lte", 12, False),
        ], "any": [
            cond("newHigh120", "eq", 1),
            cond("rsi6", "lte", 35, True, 1, "lower"),
            cond("pctFrom52wHigh", "lte", -20, True, 1, "lower"),
        ], "not": []},
    },
    {
        "name": "实验-全市场新高低波",
        "description": "不限定股票/ETF，120 日新高与低波动组合；检验趋势信号跨品种是否稳定",
        "dsl": {"all": [
            cond("listedDays", "gte", 250), cond("newHigh120", "eq", 1),
            cond("pctFrom52wHigh", "gte", -20, True, 2, "higher"),
            cond("volatility20", "lte", 6, True, 1, "lower"),
            cond("downsideVol20", "lte", 4, False),
            cond("amplitude20", "lte", 8, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场反转",
        "description": "不限定股票/ETF，短线超卖与低波动组合；检验均值回归跨品种是否有效",
        "dsl": {"all": [
            cond("listedDays", "gte", 250), cond("rsi6", "lte", 45, True, 1, "lower"),
            cond("bias20", "lte", 0, True, 2, "lower"),
            cond("volatility20", "lte", 6, True, 1, "lower"),
            cond("downsideVol20", "lte", 4, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场趋势",
        "description": "不限定股票/ETF，120 日新高、60 日收益为正并限制波动；检验趋势信号跨品种是否有效",
        "dsl": {"all": [
            cond("listedDays", "gte", 250), cond("newHigh120", "eq", 1),
            cond("return60", "gt", 0), cond("volatility20", "lte", 8, True, 1, "lower"),
            cond("pctFrom52wHigh", "gte", -15, True, 2, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-跨市场动量",
        "description": "不限定股票/ETF，以 20 日和 60 日收益为正、站上 MA20 且限制波动筛选；不依赖新高标签",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("return20", "gt", 3, True, 2, "higher"),
            cond("return60", "gt", 0, False), cond("aboveMa20", "eq", 1),
            cond("volumeRatio5", "lte", 3, False),
            cond("volatility20", "lte", 15, True, 1, "lower"),
            cond("pctFrom52wHigh", "gte", -20, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-全市场低波上升",
        "description": "不限定股票/ETF，要求 60 日收益为正且站上 MA60，再叠加低波动；检验低波组合能否避开下跌债券",
        "dsl": {"all": [
            cond("listedDays", "gte", 250), cond("return60", "gt", 1),
            cond("aboveMa60", "eq", 1),
            cond("volatility20", "lte", 8, True, 2, "lower"),
            cond("downsideVol20", "lte", 5, True, 1, "lower"),
            cond("pctFrom52wHigh", "gte", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-ETF低波",
        "description": "仅 ETF/非股票标的，低波动与低下行风险；检验 ETF 防守组合",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-ETF低波-止损3",
        "description": "ETF 低波策略的独立 3% 固定止损复核变体；手续费不变",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-ETF低波-时间止损10",
        "description": "ETF 低波策略的独立 10 个交易日未盈利退出变体；手续费不变",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-ETF趋势",
        "description": "仅 ETF/非股票标的，120 日新高、中期收益为正并限制波动；检验 ETF 趋势组合",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("newHigh120", "eq", 1), cond("return60", "gt", 0),
            cond("volatility20", "lte", 8, True, 1, "lower"),
            cond("pctFrom52wHigh", "gte", -15, True, 2, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-ETF新高",
        "description": "仅 ETF/非股票标的，60/120 日新高和温和量能；检验 ETF 突破组合",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("newHigh120", "eq", 1), cond("newHigh60", "eq", 1),
            cond("volumeRatio20", "lte", 3, False),
            cond("volatility20", "lte", 8, False),
            cond("pctFrom52wHigh", "gte", -10, True, 2, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-ETF反转",
        "description": "仅 ETF/非股票标的，短线超卖和低波动；检验 ETF 均值回归组合",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("rsi6", "lte", 45, True, 1, "lower"),
            cond("bias20", "lte", 0, True, 2, "lower"),
            cond("volatility20", "lte", 6, True, 1, "lower"),
            cond("downsideVol20", "lte", 4, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-ETF现金防守",
        "description": "仅 ETF/非股票标的，极低波动且靠近 52 周低点，尝试排除近期下跌的中长债；覆盖基础、趋势、风险因子",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 1, True, 2, "lower"),
            cond("downsideVol20", "lte", 1, True, 1, "lower"),
            cond("amplitude20", "lte", 1, False),
            cond("pctFrom52wLow", "lte", 1, False),
            cond("rsi6", "lte", 60, False),
            cond("pctFrom52wHigh", "gte", -5, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-复核超跌企稳",
        "description": "复核已有超跌企稳策略：距 52 周高点较远且近期波动低；按完整两段区间重新验证",
        "dsl": {"all": [
            cond("pctFrom52wHigh", "lte", -30, True, 1, "lower"),
            cond("volatility20", "lte", 3, True, 1, "lower"),
        ], "any": [], "not": []},
    },
    # ------------------------------------------------------------------
    # G6：稳定性终极测试（目标：A / B / SUB2 三段全为正）
    # ------------------------------------------------------------------
    {
        "name": "实验-G6-浅回撤强趋势",
        "description": "浅回撤低波池 + bias60 温和区间评分权重 2，强化趋势倾斜",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("bias60", "gt", -15, True, 2, "band",
                 band_low=-10, band_high=30, band_low_tolerance=10, band_high_tolerance=30),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G6-浅回撤趋势门",
        "description": "浅回撤低波池 + bias60 > -10% 硬门（低换手慢趋势）",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("bias60", "gt", -10, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G6-极浅微趋势",
        "description": "极浅回撤低波池 + bias60 小区间 [-5, 15] 评分，温和倾斜",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -5, False),
            cond("bias60", "gt", -10, True, 1, "band",
                 band_low=-5, band_high=15, band_low_tolerance=10, band_high_tolerance=15),
        ], "any": [], "not": []},
    },
    # ------------------------------------------------------------------
    # G5：围绕双赢（低波重权 + bias60 软趋势）做最后微调
    # ------------------------------------------------------------------
    {
        "name": "实验-G5-低波超重权",
        "description": "低波权重加大到 6:3:1，进一步稳定排名、压缩换手",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 6, "lower"),
            cond("downsideVol20", "lte", 3, True, 3, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G5-低波重权带",
        "description": "趋势项改为 bias60 区间 [-15, 25]，避免极端强势与深跌两类",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
            cond("bias60", "gt", -15, True, 1, "band",
                 band_low=-15, band_high=25, band_low_tolerance=15, band_high_tolerance=25),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G5-低波重权窄震",
        "description": "收紧振幅过滤到 5%，减少波动扩张标的以压缩换手",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 5, False),
            cond("pctFrom52wHigh", "gte", -25, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G5-低波重权重回撤",
        "description": "距高点过滤收紧到 -15%，验证回撤约束对双赢的增益",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -15, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G5-低波重权慢门",
        "description": "叠加 return60 > -10% 慢趋势门，过滤中期走弱标的",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
            cond("return60", "gt", -10, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    # ------------------------------------------------------------------
    # G4：验证低换手下能否稳住双赢（价值倾斜 / 慢动量 / 重权低波）
    # ------------------------------------------------------------------
    {
        "name": "实验-G4-浅回撤价值",
        "description": "浅回撤低波池叠加 PE ≤ 25 与 PB ≤ 3 估值过滤（低换手价值倾斜）",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("peTtm", "gt", 0, False),
            cond("peTtm", "lte", 25, False),
            cond("pb", "gt", 0, False),
            cond("pb", "lte", 3, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G4-极浅慢动量",
        "description": "极浅回撤低波池叠加 return60 慢动量评分（重权低波 4:2:1）",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -5, False),
            cond("return60", "gt", 0, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G4-极浅趋势门",
        "description": "极浅回撤低波池叠加 bias60 > -15% 慢趋势门（不评分、低换手）",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -5, False),
            cond("bias60", "gt", -15, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G4-浅回撤价值趋势",
        "description": "浅回撤低波池（重权 4:2:1）叠加 PE/PB 过滤与 bias60 软趋势评分",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("peTtm", "gt", 0, False),
            cond("peTtm", "lte", 25, False),
            cond("pb", "gt", 0, False),
            cond("pb", "lte", 3, False),
            cond("bias60", "gt", -10, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G4-低波重权纯",
        "description": "仅低波重权（4:2）无趋势项，验证 重权趋势分 的收益是否来自权重本身",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G4-低波重权浅回撤",
        "description": "低波重权（4:2:1）+ 浅回撤过滤（-10%）+ 软趋势评分，寻找低回撤双赢",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G3-低波浅回撤趋势分",
        "description": "浅回撤过滤（-10%）叠加 bias60 软趋势评分，兼顾低换手与趋势倾斜",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G3-低波极浅回撤",
        "description": "低波池距高点过滤收紧到 -5%，最大化避开走弱标的",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -5, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G3-低波浅回撤慢势",
        "description": "浅回撤过滤叠加 return60 > -10% 慢趋势门（不作为评分，控制换手）",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("return60", "gt", -10, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G3-低波重权趋势分",
        "description": "提高低波权重（4:2:1）让排名更稳，趋势分降为辅助",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 4, "lower"),
            cond("downsideVol20", "lte", 3, True, 2, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G3-低波趋势带",
        "description": "趋势分改为 bias60 区间 [-10, 30]，偏好温和上行而非极端强势",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
            cond("bias60", "gt", -15, True, 1, "band",
                 band_low=-10, band_high=30, band_low_tolerance=15, band_high_tolerance=30),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G3-浅回撤趋势带",
        "description": "浅回撤过滤 + bias60 温和区间评分（[-10, 20]）的组合",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
            cond("bias60", "gt", -15, True, 1, "band",
                 band_low=-10, band_high=20, band_low_tolerance=15, band_high_tolerance=20),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-低波纯排名",
        "description": "低波池去掉距高点评分，减少排名抖动带来的换手；验证低换手低波的 B 段表现",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-低波趋势分",
        "description": "低波池用 bias60 作为软性趋势评分（>-15% 且越大越好），减少硬过滤换手",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -25, False),
            cond("bias60", "gt", -15, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-低波浅回撤",
        "description": "低波池收紧距高点过滤到 -10%，试图在 B 段提前剔除走弱的债券类标的",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-低波慢趋势门",
        "description": "低波池使用 return60 > -5% 的慢趋势门，比 MA60 更平滑、少抖动",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("return60", "gt", -5, False),
            cond("pctFrom52wHigh", "gte", -20, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-ETF低波浅回撤",
        "description": "ETF 低波池收紧距高点过滤到 -10%，验证 B 段债券回撤能否被剔除",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("pctFrom52wHigh", "gte", -10, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-ETF低波慢趋势",
        "description": "ETF 低波池叠加 return60 > 0 慢趋势门，验收 ETF 趋势过滤的成本影响",
        "dsl": {"all": [
            cond("stockOnly", "eq", 0), cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 5, True, 2, "lower"),
            cond("downsideVol20", "lte", 3, True, 1, "lower"),
            cond("amplitude20", "lte", 6, False),
            cond("return60", "gt", 0, False),
            cond("pctFrom52wHigh", "gte", -15, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-低波宽池浅回撤",
        "description": "放宽低波阈值但收紧距高点过滤，检验容量与成本的平衡",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 10, True, 2, "lower"),
            cond("downsideVol20", "lte", 6, True, 1, "lower"),
            cond("amplitude20", "lte", 12, False),
            cond("pctFrom52wHigh", "gte", -10, False),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-低波新高门",
        "description": "低波池要求 120 日新高或站上 MA60 任一状态，验证趋势状态并联是否兼顾两段",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 6, True, 2, "lower"),
            cond("downsideVol20", "lte", 4, True, 1, "lower"),
            cond("pctFrom52wHigh", "gte", -20, False),
        ], "any": [
            cond("newHigh120", "eq", 1),
            cond("aboveMa60", "eq", 1),
        ], "not": []},
    },
    {
        "name": "实验-G2-低波便宜趋势",
        "description": "低波池叠加低估值过滤，检验价值属性对 B 段收益的贡献",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 6, True, 2, "lower"),
            cond("downsideVol20", "lte", 4, True, 1, "lower"),
            cond("peTtm", "gt", 0, False),
            cond("peTtm", "lte", 20, False),
            cond("pb", "gt", 0, False),
            cond("pb", "lte", 2.5, False),
            cond("pctFrom52wHigh", "gte", -20, True, 1, "higher"),
        ], "any": [], "not": []},
    },
    {
        "name": "实验-G2-低波双状态估值",
        "description": "低波低估池（PE ≤ 15 且剔除亏损）中允许接近新高或站上 MA60 任一趋势状态",
        "dsl": {"all": [
            cond("listedDays", "gte", 250),
            cond("volatility20", "lte", 6, True, 2, "lower"),
            cond("downsideVol20", "lte", 4, True, 1, "lower"),
            cond("amplitude20", "lte", 8, False),
            cond("peTtm", "lte", 15, False),
        ], "any": [
            cond("pctFrom52wHigh", "gte", -5),
            cond("aboveMa60", "eq", 1),
        ], "not": [
            cond("peTtm", "lte", 0),
        ]},
    },
]

WINDOWS = {
    # 结束日按含义写成 2024-01-01，实际最后一个交易日为 2023-12-29。
    "HISTORICAL": ("2018-01-02", "2024-01-01"),
    "RECENT": ("2024-01-02", "2026-09-11"),
    # 稳健性检查用子区间（仅对最终候选使用）
    "SUB1": ("2018-01-02", "2020-12-31"),
    "SUB2": ("2021-01-04", "2023-12-29"),
    "SUB3": ("2025-01-02", "2026-09-11"),
}

# 本轮研究严格按用户要求只比较日频；交易成本保持原项目默认值，不随候选策略调整。
FREQUENCIES = {strategy["name"]: ["DAILY"] for strategy in STRATEGIES}

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


def delete_strategy(base_url, db_path, strategy_id):
    """通过项目删除接口清理策略及其回测记录，避免直接改表留下半套数据。"""
    post(base_url, "/fragments/screener/strategies/%d/delete" % strategy_id, {})
    if db(db_path, "SELECT id FROM strategy WHERE id = ?", (strategy_id,)):
        raise RuntimeError("策略删除失败：#%d" % strategy_id)
    return db(db_path, "SELECT COUNT(*) AS n FROM backtest_task WHERE strategy_id = ?",
              (strategy_id,))[0]["n"]


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
                    "key": "%s|%s|%s|SL%s|TS%s" % (
                        strategy["name"], window, frequency,
                        args.stop_loss_pct, args.time_stop_days),
                    "strategy": strategy["name"],
                    "window": window, "start": start, "end": end,
                    "frequency": frequency,
                })
    return jobs


def run_all(args):
    state = load_state(args.out)
    state_lock = threading.Lock()

    selected = [strategy for strategy in STRATEGIES
                if not args.only
                or any(key in strategy["name"] for key in args.only)]
    if not selected:
        log("没有匹配的策略：%s" % ",".join(args.only))
        return 1

    log("创建/更新策略…")
    ids = {}
    for strategy in selected:
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
    if args.delete_losing:
        cleanup_losing(args, ids, build_jobs(args))
    return 1 if failed else 0


def cleanup_losing(args, ids, jobs):
    """保留至少一个区间盈利的策略；其余本轮策略通过业务接口删除。"""
    rows = collect(args)
    by_strategy = {}
    for row in rows:
        by_strategy.setdefault(row["strategy_name"], []).append(row)
    expected = {}
    for job in jobs:
        expected[job["strategy"]] = expected.get(job["strategy"], 0) + 1

    for name, strategy_id in ids.items():
        result_rows = by_strategy.get(name, [])
        successful = [row for row in result_rows
                      if row["status"] == "SUCCESS" and row["cumulative_return"] is not None]
        profitable = any(row["cumulative_return"] > 0 for row in successful)
        complete = len(successful) == expected.get(name, 0)
        if complete and profitable:
            log("保留策略 #%d %s（至少一个区间盈利）" % (strategy_id, name))
            continue
        removed = delete_strategy(args.base_url, args.db, strategy_id)
        log("删除策略 #%d %s（无完整盈利区间，清理 %d 条回测记录）" %
            (strategy_id, name, removed))


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
    parser.add_argument("--windows", default="HISTORICAL,RECENT", help="区间名，逗号分隔（HISTORICAL/RECENT）")
    parser.add_argument("--freqs", default="", help="频率过滤，逗号分隔（DAILY/WEEKLY/MONTHLY）")
    parser.add_argument("--concurrency", type=int, default=3, help="回测并发数")
    parser.add_argument("--stop-loss-pct", type=int, default=12,
                        help="固定止损百分比；费用参数不受此参数影响（默认 12）")
    parser.add_argument("--time-stop-days", type=int, default=0,
                        help="时间止损交易日数；0 表示关闭（默认 0）")
    parser.add_argument("--force", action="store_true", help="忽略已有成功任务，全部重跑")
    parser.add_argument("--create-only", action="store_true", help="只创建策略")
    parser.add_argument("--delete-losing", action="store_true",
                        help="回测结束后删除本轮无盈利区间或任务未完成的策略；至少一段盈利则保留")
    parser.add_argument("--report", action="store_true", help="仅输出已有结果")
    args = parser.parse_args()

    args.only = [key.strip() for key in args.only.split(",") if key.strip()]
    args.windows = [key.strip().upper() for key in args.windows.split(",") if key.strip()]
    args.freqs = [key.strip().upper() for key in args.freqs.split(",") if key.strip()]
    BACKTEST_PARAMS["stopLossPct"] = args.stop_loss_pct
    BACKTEST_PARAMS["timeStopDays"] = args.time_stop_days
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
