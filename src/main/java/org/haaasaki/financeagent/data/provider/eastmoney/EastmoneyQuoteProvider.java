package org.haaasaki.financeagent.data.provider.eastmoney;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 东财日K Provider（腾讯 WAF 限流时的备源）。
 *
 * <p>接口：
 * <pre>
 * GET https://push2his.eastmoney.com/api/qt/stock/kline/get
 *     ?secid={market}.{code}&amp;klt=101&amp;fqt=1
 *     &amp;beg=yyyyMMdd&amp;end=yyyyMMdd&amp;lmt=100000
 * </pre>
 *
 * <p>解析要点：
 * <ul>
 *   <li>{@code data.klines} 每条为
 *       {@code 日期,开,收,高,低,成交量(手),成交额(元),振幅%,涨跌幅%,涨跌额,换手率%}</li>
 *   <li>成交量单位为「手」，落库前 ×100 换算为股；成交额单位为元，可直接写入</li>
 *   <li>{@code fqt=1} 为前复权，与库内 {@code QFQ} 口径一致</li>
 *   <li>市场标识：沪市（含沪市 ETF / 指数）= 1，深市与北交所 = 0</li>
 * </ul>
 *
 * <p>一次请求可取完整区间，不需要像腾讯那样按 3 年分段。
 */
public class EastmoneyQuoteProvider implements QuoteProvider {

    private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String KLINE_PATH = "/api/qt/stock/kline/get";
    private static final String FIELDS = "&fields1=f1,f2,f3,f4,f5,f6"
            + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61";

    private final ThrottledHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String host;

    public EastmoneyQuoteProvider(ThrottledHttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  @Value("${finance.providers.quote.eastmoney-host:https://push2his.eastmoney.com}")
                                  String host) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.host = host;
    }

    @Override
    public String name() {
        return "EASTMONEY_KLINE";
    }

    @Override
    public List<DailyBar> fetchDailyBars(String instrumentId, LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("起始日期晚于结束日期: " + start + " > " + end);
        }
        String url = host + KLINE_PATH
                + "?secid=" + toSecid(instrumentId)
                + "&klt=101&fqt=1"
                + "&beg=" + start.format(URL_DATE)
                + "&end=" + end.format(URL_DATE)
                + "&lmt=100000" + FIELDS;
        String body = httpClient.get(url, StandardCharsets.UTF_8);
        return fillDerivedFields(parse(instrumentId, start, end, body));
    }

    private List<DailyBar> parse(String instrumentId, LocalDate start, LocalDate end, String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new DataSyncException("解析东财日K响应失败: " + instrumentId, e);
        }

        JsonNode data = root.path("data");
        if (!data.isObject()) {
            return List.of();
        }
        JsonNode rows = data.path("klines");
        if (!rows.isArray() || rows.isEmpty()) {
            return List.of();
        }

        Map<LocalDate, DailyBar> merged = new LinkedHashMap<>();
        LocalDate previous = null;
        for (JsonNode row : rows) {
            String text = row.asText();
            String[] fields = text.split(",", -1);
            if (fields.length < 6) {
                throw new DataIntegrityException(
                        "东财日K字段数异常（至少 6，实际 " + fields.length + "）: " + instrumentId + " " + text);
            }
            LocalDate date = LocalDate.parse(fields[0]);
            if (date.isBefore(start) || date.isAfter(end)) {
                throw new DataIntegrityException(
                        "东财返回了请求区间外的日期 " + date + ": " + instrumentId);
            }
            if (previous != null && !date.isAfter(previous)) {
                throw new DataIntegrityException(
                        "东财日K日期未升序或重复: " + instrumentId + " " + previous + " -> " + date);
            }
            previous = date;

            double open = Double.parseDouble(fields[1]);
            double close = Double.parseDouble(fields[2]);
            double high = Double.parseDouble(fields[3]);
            double low = Double.parseDouble(fields[4]);
            long volumeShares = Math.round(Double.parseDouble(fields[5]) * 100);
            Double amount = parseNullable(fields, 6);
            Double pctChg = percent(parseNullable(fields, 8));
            Double turnover = percent(parseNullable(fields, 10));

            merged.put(date, new DailyBar(instrumentId, date, open, high, low, close,
                    null, volumeShares, amount, pctChg, turnover,
                    DailyBar.ADJ_QFQ, name()));
        }
        return List.copyOf(merged.values());
    }

    /** 首根没有前收盘；其余由上一交易日收盘补出，涨跌幅保持接口原值。 */
    private List<DailyBar> fillDerivedFields(List<DailyBar> bars) {
        List<DailyBar> filled = new ArrayList<>(bars.size());
        Double previousClose = null;
        for (DailyBar bar : bars) {
            Double preClose = previousClose;
            Double pctChg = preClose == null ? null : bar.pctChg();
            filled.add(new DailyBar(bar.instrumentId(), bar.tradeDate(), bar.open(), bar.high(),
                    bar.low(), bar.close(), preClose, bar.volume(), bar.amount(), pctChg,
                    bar.turnoverRate(), bar.adjType(), bar.source()));
            previousClose = bar.close();
        }
        return List.copyOf(filled);
    }

    /** {@code 600000.SH} → {@code 1.600000}；深市/北交所 → {@code 0.xxxxxx}。 */
    private String toSecid(String instrumentId) {
        int dot = instrumentId == null ? -1 : instrumentId.lastIndexOf('.');
        if (dot <= 0 || dot == instrumentId.length() - 1) {
            throw new DataIntegrityException(
                    "非法 instrumentId（期望 {code}.{exchange}）: " + instrumentId);
        }
        String code = instrumentId.substring(0, dot);
        String exchange = instrumentId.substring(dot + 1).toUpperCase();
        return switch (exchange) {
            case "SH" -> "1." + code;
            case "SZ", "BJ" -> "0." + code;
            default -> throw new DataIntegrityException("非法交易所: " + instrumentId);
        };
    }

    private Double parseNullable(String[] fields, int index) {
        if (index >= fields.length || fields[index].isBlank() || "-".equals(fields[index])) {
            return null;
        }
        try {
            return Double.parseDouble(fields[index]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 东财百分比字段是 12.5 表示 12.5%，契约要求小数 0.125。 */
    private Double percent(Double value) {
        return value == null ? null : value / 100.0;
    }
}
