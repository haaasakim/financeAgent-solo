package org.haaasaki.financeagent.data.provider.tencent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.config.QuoteProperties;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 腾讯日K解析测试。
 *
 * <p>用固定响应样本验证三个最容易写错的点：字段顺序（开收高低）、成交量单位（手→股）、
 * 以及 count 超限时的静默降级检测。不发真实网络请求。
 */
@ExtendWith(MockitoExtension.class)
class TencentQuoteProviderTest {

    /** 真实响应结构，注意单条数据是 [日期, 开, 收, 高, 低, 成交量(手)]。 */
    private static final String SAMPLE = """
            {"code":0,"data":{"sh600000":{"qfqday":[
              ["2024-09-02","7.540","7.560","7.670","7.520","421786.000"],
              ["2024-09-03","7.550","7.420","7.600","7.370","412572.000"]
            ]}}}
            """;

    private static final String PRIMARY = "https://web.ifzq.gtimg.cn";
    private static final String FALLBACK = "https://ifzq.gtimg.cn";

    @Mock
    private ThrottledHttpClient httpClient;

    private TencentQuoteProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TencentQuoteProvider(httpClient, new ObjectMapper(), 3,
                new QuoteProperties(List.of(PRIMARY, FALLBACK)));
    }

    @Test
    @DisplayName("字段顺序按「开收高低」解析，而非常见的「开高低收」")
    void parsesFieldOrderAsOpenCloseHighLow() {
        given(httpClient.get(anyString(), any())).willReturn(SAMPLE);

        List<DailyBar> bars = provider.fetchDailyBars(
                "600000.SH", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));

        assertThat(bars).hasSize(2);
        DailyBar first = bars.get(0);
        assertThat(first.tradeDate()).isEqualTo(LocalDate.of(2024, 9, 2));
        assertThat(first.open()).isEqualTo(7.540);
        assertThat(first.close()).isEqualTo(7.560);
        assertThat(first.high()).isEqualTo(7.670);
        assertThat(first.low()).isEqualTo(7.520);

        // 若把字段顺序误当成「开高低收」，会出现 low > close 的矛盾价格，此断言可捕获
        assertThat(first.high()).isGreaterThanOrEqualTo(Math.max(first.open(), first.close()));
        assertThat(first.low()).isLessThanOrEqualTo(Math.min(first.open(), first.close()));
    }

    @Test
    @DisplayName("成交量由「手」换算为「股」（×100）")
    void convertsVolumeFromLotsToShares() {
        given(httpClient.get(anyString(), any())).willReturn(SAMPLE);

        List<DailyBar> bars = provider.fetchDailyBars(
                "600000.SH", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));

        assertThat(bars.get(0).volume()).isEqualTo(421786L * 100);
        assertThat(bars.get(1).volume()).isEqualTo(412572L * 100);
    }

    @Test
    @DisplayName("派生字段：前收盘取上一交易日收盘，涨跌幅由前收盘计算")
    void fillsDerivedFields() {
        given(httpClient.get(anyString(), any())).willReturn(SAMPLE);

        List<DailyBar> bars = provider.fetchDailyBars(
                "600000.SH", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));

        // 首条无前一日数据，前收盘与涨跌幅为空（缺失语义，不得填 0）
        assertThat(bars.get(0).preClose()).isNull();
        assertThat(bars.get(0).pctChg()).isNull();

        // 第二条前收盘 = 首条收盘 7.560，涨跌幅 = (7.420-7.560)/7.560
        assertThat(bars.get(1).preClose()).isEqualTo(7.560);
        assertThat(bars.get(1).pctChg()).isCloseTo((7.420 - 7.560) / 7.560, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("接口无数据时返回空列表，不抛异常")
    void returnsEmptyWhenNoData() {
        given(httpClient.get(anyString(), any()))
                .willReturn("{\"code\":0,\"data\":{\"sh600000\":{\"day\":[]}}}");

        List<DailyBar> bars = provider.fetchDailyBars(
                "600000.SH", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));

        assertThat(bars).isEmpty();
    }

    @Test
    @DisplayName("区间跨度大却恰好返回 640 条时，判定为接口静默降级并拒绝")
    void detectsSilentDegradation() {
        StringBuilder rows = new StringBuilder();
        LocalDate date = LocalDate.of(2016, 1, 4);
        for (int i = 0; i < 640; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("[\"").append(date).append("\",\"7.540\",\"7.560\",\"7.670\",\"7.520\",\"421786.000\"]");
            date = date.plusDays(1);
        }
        given(httpClient.get(anyString(), any()))
                .willReturn("{\"code\":0,\"data\":{\"sh600000\":{\"qfqday\":[" + rows + "]}}}");

        // 区间跨度 10 年远超 1000 天，返回恰好 640 条 → 高度疑似降级
        assertThatThrownBy(() -> provider.fetchDailyBars(
                "600000.SH", LocalDate.of(2016, 1, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("静默降级");
    }

    @Test
    @DisplayName("返回了请求区间外的日期时拒绝整批")
    void rejectsOutOfRangeDate() {
        given(httpClient.get(anyString(), any())).willReturn("""
                {"code":0,"data":{"sh600000":{"qfqday":[
                  ["2024-10-08","7.540","7.560","7.670","7.520","421786.000"]
                ]}}}
                """);

        assertThatThrownBy(() -> provider.fetchDailyBars(
                "600000.SH", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30)))
                .isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("区间外");
    }

    @Test
    @DisplayName("主域名不可用时回退到备用域名")
    void fallsBackToNextHost() {
        given(httpClient.get(startsWith(PRIMARY), any()))
                .willThrow(new DataSyncException("HTTP 501 - WAF"));
        given(httpClient.get(startsWith(FALLBACK), any())).willReturn(SAMPLE);

        List<DailyBar> bars = provider.fetchDailyBars(
                "600000.SH", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));

        assertThat(bars).hasSize(2);
    }

    @Test
    @DisplayName("非法 instrumentId 直接拒绝")
    void rejectsMalformedInstrumentId() {
        assertThatThrownBy(() -> provider.fetchDailyBars(
                "600000", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30)))
                .isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("非法 instrumentId");
    }
}
