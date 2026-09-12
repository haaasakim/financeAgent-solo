package org.haaasaki.financeagent.data.provider.sina;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.model.InstrumentSnapshot;
import org.haaasaki.financeagent.data.provider.SnapshotProvider;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 新浪全市场标的快照数据源。
 *
 * <pre>
 * GET https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/
 *     Market_Center.getHQNodeData?page=1&amp;num=100&amp;sort=symbol&amp;asc=1&amp;node=hs_a
 * </pre>
 *
 * <p>两个节点分别取股票与 ETF（实测 {@code hs_etf} 为空节点，正确节点是 {@code etf_hq_fund}）：
 * <ul>
 *   <li>{@code hs_a} —— 全部 A 股，含北交所</li>
 *   <li>{@code etf_hq_fund} —— 场内 ETF</li>
 * </ul>
 *
 * <p>单位换算（新浪原始单位与本站契约不同，必须转换）：
 * <ul>
 *   <li>{@code mktcap} / {@code nmc}：万元 → 元（×10000）</li>
 *   <li>{@code turnoverratio}：百分数数值 → 小数（÷100）。实测 0.94246 表示 0.94%</li>
 *   <li>{@code per}：非正数视为「未提供」，置为 null（契约规定亏损时为空，不得填 0）</li>
 * </ul>
 */
@Component
public class SinaSnapshotProvider implements SnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(SinaSnapshotProvider.class);

    private static final String BASE_URL =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData";
    private static final String NODE_STOCK = "hs_a";
    private static final String NODE_ETF = "etf_hq_fund";
    private static final int PAGE_SIZE = 100;
    /** 保护上限：正常约 55 页（5,400 只），出现异常时避免无限翻页。 */
    private static final int MAX_PAGES = 200;

    private final ThrottledHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SinaSnapshotProvider(ThrottledHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "SINA_MARKET_CENTER";
    }

    @Override
    public List<InstrumentSnapshot> fetchStocks() {
        return fetchAll(NODE_STOCK);
    }

    @Override
    public List<InstrumentSnapshot> fetchEtfs() {
        return fetchAll(NODE_ETF);
    }

    private List<InstrumentSnapshot> fetchAll(String node) {
        List<InstrumentSnapshot> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = BASE_URL + "?page=" + page + "&num=" + PAGE_SIZE
                    + "&sort=symbol&asc=1&node=" + node;
            String body = httpClient.get(url, StandardCharsets.UTF_8);
            List<InstrumentSnapshot> batch = parse(body, node);
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        log.info("新浪快照拉取完成 node={} 共 {} 条", node, all.size());
        return all;
    }

    private List<InstrumentSnapshot> parse(String body, String node) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new DataSyncException("解析新浪快照失败 node=" + node, e);
        }
        if (!root.isArray()) {
            return List.of();
        }

        List<InstrumentSnapshot> list = new ArrayList<>(root.size());
        for (JsonNode row : root) {
            String symbol = text(row, "symbol");
            String code = text(row, "code");
            String name = text(row, "name");
            if (symbol == null || code == null) {
                continue;
            }
            list.add(new InstrumentSnapshot(
                    symbol,
                    code,
                    name,
                    positiveOrNull(number(row, "per")),
                    positiveOrNull(number(row, "pb")),
                    tenThousandToYuan(number(row, "mktcap")),
                    tenThousandToYuan(number(row, "nmc")),
                    percentToRatio(number(row, "turnoverratio"))));
        }
        return list;
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Double number(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double positiveOrNull(Double value) {
        return value == null || value <= 0 ? null : value;
    }

    /** 万元 → 元 */
    private Double tenThousandToYuan(Double wan) {
        return wan == null ? null : wan * 10_000;
    }

    /** 百分数数值 → 小数 */
    private Double percentToRatio(Double percent) {
        return percent == null ? null : percent / 100.0;
    }
}
