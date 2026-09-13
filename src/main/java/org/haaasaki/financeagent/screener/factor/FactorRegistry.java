package org.haaasaki.financeagent.screener.factor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 因子注册表：选股模块唯一的因子白名单与 SQL 表达式来源。
 *
 * <p>技术因子的数据来自 {@code factor_daily} 最新一行；基础因子来自 {@code instrument}；
 * 估值因子来自 {@code market_snapshot}（快照仅存最新值，因此估值条件是「当前截面」）。
 *
 * <p>财务因子（股息率 / ROE / 资产负债率 / 营收同比 / 净利同比）依赖财报数据源，暂以
 * {@code available=false} 占位，页面灰显不可选，避免假交互。
 */
@Component
public class FactorRegistry {

    /** 固定分组顺序。 */
    private static final List<String> GROUP_ORDER =
            List.of("基础", "估值", "质量", "成长", "趋势", "风险");

    private final List<FactorDefinition> definitions;
    private final Map<String, FactorDefinition> byKey;

    public FactorRegistry() {
        List<FactorDefinition> list = List.of(
                // 基础
                new FactorDefinition("excludeSt", "基础", "非 ST", FactorKind.BOOLEAN,
                        "CASE WHEN i.name LIKE '%ST%' THEN 0 ELSE 1 END",
                        "CASE WHEN i.name LIKE '%ST%' THEN 0 ELSE 1 END",
                        "", true),
                new FactorDefinition("tradable", "基础", "非停牌（最近在交易）", FactorKind.BOOLEAN,
                        "CASE WHEN (SELECT MAX(d.trade_date) FROM daily_bar d"
                                + " WHERE d.instrument_id = i.instrument_id)"
                                + " = (SELECT MAX(trade_date) FROM daily_bar) THEN 1 ELSE 0 END",
                        "CASE WHEN EXISTS (SELECT 1 FROM daily_bar d"
                                + " WHERE d.instrument_id = i.instrument_id"
                                + " AND d.trade_date = f.trade_date) THEN 1 ELSE 0 END",
                        "", true),
                new FactorDefinition("listedDays", "基础", "上市天数", FactorKind.NUMBER,
                        "CAST(julianday('now', 'localtime') - julianday(i.listed_at) AS INTEGER)",
                        "CAST(julianday(f.trade_date) - julianday(i.listed_at) AS INTEGER)",
                        "天", true),

                // 估值（快照为最新截面；历史截面来自 valuation_daily，见 WI-007）
                new FactorDefinition("peTtm", "估值", "PE-TTM", FactorKind.NUMBER,
                        "ms.pe_ttm", "v.pe_ttm", "倍", true),
                new FactorDefinition("pb", "估值", "PB", FactorKind.NUMBER,
                        "ms.pb", "v.pb_mrq", "倍", true),
                new FactorDefinition("dividendYield", "估值", "股息率", FactorKind.NUMBER,
                        null, null, "%", false),

                // 质量 / 成长（财务，占位）
                new FactorDefinition("roeTtm", "质量", "ROE-TTM", FactorKind.NUMBER,
                        null, null, "%", false),
                new FactorDefinition("debtRatio", "质量", "资产负债率", FactorKind.NUMBER,
                        null, null, "%", false),
                new FactorDefinition("revenueYoy", "成长", "营收同比", FactorKind.NUMBER,
                        null, null, "%", false),
                new FactorDefinition("profitYoy", "成长", "净利同比", FactorKind.NUMBER,
                        null, null, "%", false),

                // 趋势（factor_daily）
                new FactorDefinition("return20", "趋势", "20 日收益", FactorKind.NUMBER,
                        "(f.return_20 * 100)", "(f.return_20 * 100)", "%", true),
                new FactorDefinition("return60", "趋势", "60 日收益", FactorKind.NUMBER,
                        "(f.return_60 * 100)", "(f.return_60 * 100)", "%", true),
                new FactorDefinition("aboveMa60", "趋势", "站上 MA60", FactorKind.BOOLEAN,
                        "CASE WHEN (SELECT d.close FROM daily_bar d"
                                + " WHERE d.instrument_id = i.instrument_id"
                                + " ORDER BY d.trade_date DESC LIMIT 1) > f.ma60 THEN 1 ELSE 0 END",
                        "CASE WHEN (SELECT d.close FROM daily_bar d"
                                + " WHERE d.instrument_id = i.instrument_id"
                                + " AND d.trade_date = f.trade_date) > f.ma60 THEN 1 ELSE 0 END",
                        "", true),
                new FactorDefinition("ma20GtMa60", "趋势", "MA20 > MA60", FactorKind.BOOLEAN,
                        "CASE WHEN f.ma20 > f.ma60 THEN 1 ELSE 0 END",
                        "CASE WHEN f.ma20 > f.ma60 THEN 1 ELSE 0 END",
                        "", true),

                // 风险
                new FactorDefinition("volatility20", "风险", "20 日波动率", FactorKind.NUMBER,
                        "(f.volatility_20 * 100)", "(f.volatility_20 * 100)", "%", true),
                new FactorDefinition("pctFrom52wHigh", "风险", "距 52 周高点", FactorKind.NUMBER,
                        "(f.pct_from_52w_high * 100)", "(f.pct_from_52w_high * 100)", "%", true));

        this.definitions = List.copyOf(list);
        Map<String, FactorDefinition> map = new LinkedHashMap<>();
        for (FactorDefinition definition : list) {
            map.put(definition.key(), definition);
        }
        this.byKey = Map.copyOf(map);
    }

    public List<FactorDefinition> all() {
        return definitions;
    }

    public FactorDefinition find(String key) {
        return key == null ? null : byKey.get(key);
    }

    /** 页面左栏的因子目录，按固定分组顺序，包含灰显的财务因子。 */
    public List<FactorGroup> groups() {
        Map<String, List<FactorDefinition>> grouped = new LinkedHashMap<>();
        for (String name : GROUP_ORDER) {
            grouped.put(name, new ArrayList<>());
        }
        for (FactorDefinition definition : definitions) {
            grouped.computeIfAbsent(definition.group(), k -> new ArrayList<>()).add(definition);
        }
        List<FactorGroup> groups = new ArrayList<>();
        grouped.forEach((name, factors) -> {
            if (!factors.isEmpty()) {
                groups.add(new FactorGroup(name, List.copyOf(factors)));
            }
        });
        return groups;
    }
}
