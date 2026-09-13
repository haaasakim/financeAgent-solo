package org.haaasaki.financeagent.data.provider.tencent;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.config.QuoteProperties;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 腾讯日K数据源。
 *
 * <pre>
 * GET {host}/appstock/app/fqkline/get?param={symbol},day,{start},{end},{count},qfq
 * 返回 data.{symbol}.qfqday = [[日期, 开, 收, 高, 低, 成交量(手)], ...]
 * </pre>
 *
 * <p>域名按 {@code finance.providers.quote.tencent-kline-hosts} 配置顺序回退：
 * 实测 {@code ifzq.gtimg.cn} 前置腾讯 WAF，对 Java/Python 客户端持续返回 501，
 * 而 {@code web.ifzq.gtimg.cn} 正常，因此默认 web 域优先。
 *
 * <p>三个必须注意的坑（详见 docs/DATA_SOURCES.md 第 2 节）：
 * <ol>
 *   <li><b>字段顺序是「开收高低」</b>，不是常见的「开高低收」</li>
 *   <li><b>成交量单位是「手」</b>，本站契约统一为股，需 ×100</li>
 *   <li><b>count 超过 800 会静默降级</b>：HTTP 仍返回 200，但只给 640 条。
 *       本实现按 {@code segment-years} 分段拉取以规避，并对「恰好 640 条」做哨兵检测</li>
 * </ol>
 */
@Component
public class TencentQuoteProvider implements QuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentQuoteProvider.class);

    private static final String KLINE_PATH = "/appstock/app/fqkline/get";

    /** 实测单次请求上限。 */
    private static final int MAX_BARS_PER_REQUEST = 800;
    /** 超限时接口静默返回的条数，用于降级哨兵检测。 */
    private static final int DEGRADED_BAR_COUNT = 640;
    /** 只有区间跨度足够大时，「恰好 640 条」才可能是降级而非真实数据。 */
    private static final long DEGRADED_SPAN_DAYS = 1000;

    private final ThrottledHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int segmentYears;
    private final QuoteProperties quoteProperties;

    /**
     * 最近一次成功的域名（粘性）。腾讯 WAF 会间歇性拦截某个域名，
     * 记住可用域名可避免每个日期分段都先撞击失败源、白耗退避时间。
     */
    private volatile String preferredHost;

    public TencentQuoteProvider(
            ThrottledHttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${finance.sync.segment-years:3}") int segmentYears,
            QuoteProperties quoteProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.segmentYears = Math.max(1, segmentYears);
        this.quoteProperties = quoteProperties;
        this.preferredHost = quoteProperties.tencentKlineHosts().get(0);
    }

    @Override
    public String name() {
        return "TENCENT_FQKLINE";
    }

    @Override
    public List<DailyBar> fetchDailyBars(String instrumentId, LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("起始日期晚于结束日期: " + start + " > " + end);
        }
        String symbol = toTencentSymbol(instrumentId);

        // 按年分段，确保每段条数不超过接口上限
        Map<LocalDate, DailyBar> merged = new LinkedHashMap<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate segmentEnd = cursor.plusYears(segmentYears).minusDays(1);
            if (segmentEnd.isAfter(end)) {
                segmentEnd = end;
            }
            for (DailyBar bar : fetchSegment(instrumentId, symbol, cursor, segmentEnd)) {
                merged.put(bar.tradeDate(), bar);
            }
            cursor = segmentEnd.plusDays(1);
        }

        return fillDerivedFields(merged.values().stream()
                .sorted((a, b) -> a.tradeDate().compareTo(b.tradeDate()))
                .toList());
    }

    private List<DailyBar> fetchSegment(String instrumentId, String symbol, LocalDate start, LocalDate end) {
        DataSyncException lastError = null;
        for (String host : hostsByPreference()) {
            String url = host + KLINE_PATH + "?param=" + symbol + ",day," + start + "," + end
                    + "," + MAX_BARS_PER_REQUEST + ",qfq";
            try {
                String body = httpClient.get(url, StandardCharsets.UTF_8);
                preferredHost = host;
                return parse(instrumentId, symbol, start, end, body);
            } catch (DataSyncException e) {
                lastError = e;
                log.warn("腾讯日K源不可用（{}），尝试下一源：{}", host, e.getMessage());
            }
        }
        throw lastError != null ? lastError : new DataSyncException("无可用腾讯日K源");
    }

    /** 上次成功的域名排最前，其余按配置顺序。 */
    private List<String> hostsByPreference() {
        List<String> configured = quoteProperties.tencentKlineHosts();
        String preferred = preferredHost;
        List<String> ordered = new ArrayList<>(configured.size());
        if (preferred != null && configured.contains(preferred)) {
            ordered.add(preferred);
        }
        for (String host : configured) {
            if (!host.equals(preferred)) {
                ordered.add(host);
            }
        }
        return ordered;
    }

    private List<DailyBar> parse(String instrumentId, String symbol,
                                 LocalDate start, LocalDate end, String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new DataSyncException("解析腾讯日K响应失败: " + symbol, e);
        }

        JsonNode data = root.path("data");
        if (!data.isObject()) {
            return List.of();
        }
        JsonNode node = data.path(symbol);
        JsonNode rows = node.path("qfqday");
        if (!rows.isArray()) {
            rows = node.path("day");
        }
        if (!rows.isArray() || rows.isEmpty()) {
            return List.of();
        }

        List<DailyBar> bars = new ArrayList<>(rows.size());
        LocalDate previous = null;
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 6) {
                throw new DataIntegrityException(
                        "腾讯日K字段数异常（期望 6，实际 " + row.size() + "）: " + symbol + " " + row);
            }
            LocalDate date = LocalDate.parse(row.get(0).asText());
            if (date.isBefore(start) || date.isAfter(end)) {
                throw new DataIntegrityException("腾讯返回了请求区间外的日期 " + date + ": " + symbol);
            }
            if (previous != null && !date.isAfter(previous)) {
                throw new DataIntegrityException(
                        "腾讯日K日期未升序或重复: " + symbol + " " + previous + " -> " + date);
            }
            previous = date;

            // 字段顺序：日期, 开, 收, 高, 低, 成交量(手)
            double open = row.get(1).asDouble();
            double close = row.get(2).asDouble();
            double high = row.get(3).asDouble();
            double low = row.get(4).asDouble();
            long volumeShares = Math.round(row.get(5).asDouble() * 100);

            bars.add(new DailyBar(instrumentId, date, open, high, low, close,
                    null, volumeShares, null, null, null, DailyBar.ADJ_QFQ, name()));
        }

        // 降级哨兵：区间跨度大却恰好返回 640 条，高度疑似接口静默降级
        if (bars.size() == DEGRADED_BAR_COUNT
                && ChronoUnit.DAYS.between(start, end) > DEGRADED_SPAN_DAYS) {
            throw new DataIntegrityException(
                    "疑似腾讯接口静默降级：区间 " + start + "~" + end + " 恰好返回 " + DEGRADED_BAR_COUNT
                            + " 条（接口 count 超限时会静默截断且 HTTP 仍为 200）。标的: " + symbol);
        }

        return bars;
    }

    /**
     * 补齐派生字段。腾讯日K不提供前收盘、涨跌幅与成交额：
     * 前收盘取上一交易日收盘价，涨跌幅由前收盘计算。
     * 成交额维持 null（缺失语义），后续可由快照数据补全。
     */
    private List<DailyBar> fillDerivedFields(List<DailyBar> bars) {
        List<DailyBar> filled = new ArrayList<>(bars.size());
        Double previousClose = null;
        for (DailyBar bar : bars) {
            Double preClose = previousClose;
            Double pctChg = null;
            if (preClose != null && preClose != 0.0) {
                pctChg = (bar.close() - preClose) / preClose;
            }
            filled.add(new DailyBar(bar.instrumentId(), bar.tradeDate(),
                    bar.open(), bar.high(), bar.low(), bar.close(),
                    preClose, bar.volume(), bar.amount(), pctChg, bar.turnoverRate(),
                    bar.adjType(), bar.source()));
            previousClose = bar.close();
        }
        return filled;
    }

    /**
     * {@code 600000.SH} → {@code sh600000}
     */
    private String toTencentSymbol(String instrumentId) {
        int dot = instrumentId.lastIndexOf('.');
        if (dot <= 0 || dot == instrumentId.length() - 1) {
            throw new DataIntegrityException("非法 instrumentId（期望 {code}.{exchange}）: " + instrumentId);
        }
        String code = instrumentId.substring(0, dot);
        String exchange = instrumentId.substring(dot + 1).toLowerCase(Locale.ROOT);
        return exchange + code;
    }
}
