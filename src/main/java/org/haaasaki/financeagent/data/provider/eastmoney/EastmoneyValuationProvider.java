package org.haaasaki.financeagent.data.provider.eastmoney;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.provider.ValuationPoint;
import org.haaasaki.financeagent.data.provider.ValuationProvider;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 东财数据中心历史估值（PE-TTM / PB-MRQ）。
 *
 * <pre>
 * GET https://datacenter-web.eastmoney.com/api/data/v1/get
 *     ?reportName=RPT_VALUEANALYSIS_DET&amp;columns=SECURITY_CODE,TRADE_DATE,PE_TTM,PB_MRQ
 *     &amp;filter=(SECURITY_CODE in ("600000","000001"))&amp;pageNumber=1&amp;pageSize=10000
 *     &amp;sortColumns=TRADE_DATE&amp;sortTypes=-1&amp;source=WEB&amp;client=WEB
 * </pre>
 *
 * <p>实测结论（2026-09-13）：
 * <ul>
 *   <li>{@code filter} 支持多代码 `in` 与 {@code (TRADE_DATE>='yyyy-MM-dd')} 过滤；
 *       <b>但多代码大批量 + 深分页会被服务端重置连接（实测 page 5 超时）</b>，
 *       因此全量回填走**逐代码单页**（pageSize 5,000 一次取全单只历史），
 *       增量按日期使用 7 天窗口分片</li>
 *   <li>服务端单页上限 10,000 行（请求 20,000 / 50,000 均只返回 10,000）</li>
 *   <li>ETF 等无估值数据返回 {@code success=false, code=9201}，视为空数据</li>
 *   <li>{@code count} 为过滤后的总数，必须与解析行数一致，否则整批拒绝</li>
 * </ul>
 *
 * <p>无效值处理：PE/PB 非正（亏损、负净资产）一律置 null，不填 0。
 */
@Component
public class EastmoneyValuationProvider implements ValuationProvider {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyValuationProvider.class);

    private static final String BASE_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";
    private static final String COLUMNS = "SECURITY_CODE,TRADE_DATE,PE_TTM,PB_MRQ";
    private static final String REPORT_NAME = "RPT_VALUEANALYSIS_DET";
    /** 单页 5,000 行（约 2 年数据）；逐代码取数时一次即可覆盖单只全部历史（约 2,100 行）。 */
    private static final int PAGE_SIZE = 5_000;
    /** 保护上限：一批 50 代码约 10 页，异常时避免无限翻页。 */
    private static final int MAX_PAGES = 2_000;
    private static final long EMPTY_DATA_CODE = 9201;

    private final ThrottledHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EastmoneyValuationProvider(ThrottledHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "EASTMONEY_DATACENTER";
    }

    @Override
    public List<ValuationPoint> fetchByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        String filter = "(SECURITY_CODE in ("
                + codes.stream().map(code -> "\"" + code + "\"").collect(Collectors.joining(","))
                + "))";
        return fetchPaged(filter);
    }

    @Override
    public List<ValuationPoint> fetchByDate(LocalDate from) {
        LocalDate today = LocalDate.now();
        List<ValuationPoint> all = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(today)) {
            // 逐日分片：单日约 5,500 行（1~2 页），避免深分页被服务端重置连接
            all.addAll(fetchPaged("(TRADE_DATE>='" + cursor + "')(TRADE_DATE<='" + cursor + "')"));
            cursor = cursor.plusDays(1);
        }
        all.sort(Comparator.comparing(ValuationPoint::tradeDate)
                .thenComparing(ValuationPoint::code));
        return all;
    }

    /** 按过滤条件分页拉取，校验总条数后按（交易日, 代码）升序返回。 */
    private List<ValuationPoint> fetchPaged(String filter) {
        String encodedFilter = URLEncoder.encode(filter, StandardCharsets.UTF_8);
        List<ValuationPoint> all = new ArrayList<>();
        long expected = -1;

        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = BASE_URL + "?reportName=" + REPORT_NAME
                    + "&columns=" + COLUMNS
                    + "&filter=" + encodedFilter
                    + "&pageNumber=" + page + "&pageSize=" + PAGE_SIZE
                    + "&sortColumns=TRADE_DATE&sortTypes=-1"
                    + "&source=WEB&client=WEB";
            Page parsed = parsePage(httpClient.get(url, StandardCharsets.UTF_8));
            if (parsed == null) {
                if (all.isEmpty()) {
                    return List.of();
                }
                break;
            }
            if (expected < 0) {
                expected = parsed.count();
            }
            all.addAll(parsed.points());
            if (all.size() >= expected || parsed.points().size() < PAGE_SIZE) {
                break;
            }
        }

        if (all.size() != expected) {
            throw new DataSyncException("估值条数校验失败 filter=" + filter
                    + "，期望 " + expected + " 实际 " + all.size());
        }
        all.sort(Comparator.comparing(ValuationPoint::tradeDate)
                .thenComparing(ValuationPoint::code));
        return all;
    }

    /** 解析一页；空数据返回 null。包级可见，供解析测试直接调用（不发真实请求）。 */
    Page parsePage(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new DataSyncException("解析东财估值响应失败", e);
        }
        JsonNode result = root.path("result");
        if (!root.path("success").asBoolean(false) || result.isMissingNode() || result.isNull()) {
            long code = root.path("code").asLong(0);
            if (code == EMPTY_DATA_CODE) {
                return null;
            }
            throw new DataSyncException("东财估值接口返回异常："
                    + root.path("message").asText("unknown"));
        }
        JsonNode data = result.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return null;
        }

        List<ValuationPoint> points = new ArrayList<>(data.size());
        for (JsonNode row : data) {
            LocalDate tradeDate = parseDate(text(row, "TRADE_DATE"));
            String code = text(row, "SECURITY_CODE");
            if (tradeDate == null || code == null) {
                continue;
            }
            points.add(new ValuationPoint(code, tradeDate,
                    positiveOrNull(number(row, "PE_TTM")),
                    positiveOrNull(number(row, "PB_MRQ"))));
        }
        return new Page(result.path("count").asLong(points.size()), points);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            log.warn("估值接口日期格式异常：{}", value);
            return null;
        }
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
        return value.isNumber() ? value.asDouble() : null;
    }

    private Double positiveOrNull(Double value) {
        return value == null || value <= 0 ? null : value;
    }

    /** 单页解析结果。包级可见供测试断言。 */
    record Page(long count, List<ValuationPoint> points) {
    }
}
