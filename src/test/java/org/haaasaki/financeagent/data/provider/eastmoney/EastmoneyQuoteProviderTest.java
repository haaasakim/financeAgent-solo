package org.haaasaki.financeagent.data.provider.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 东财日K解析测试：字段顺序、成交量手→股、百分比小数、区间校验。 */
@ExtendWith(MockitoExtension.class)
class EastmoneyQuoteProviderTest {

    private static final String SAMPLE = """
            {"rc":0,"data":{"code":"600000","market":1,"name":"浦发银行","klines":[
              "2024-09-02,7.540,7.560,7.670,7.520,421786,322000000.00,2.00,0.27,0.02,0.14",
              "2024-09-03,7.550,7.420,7.600,7.370,412572,310000000.00,1.80,-1.85,-0.14,0.13"
            ]}}
            """;

    @Mock
    private ThrottledHttpClient httpClient;

    private EastmoneyQuoteProvider provider;

    @BeforeEach
    void setUp() {
        provider = new EastmoneyQuoteProvider(httpClient, new ObjectMapper(),
                "https://push2his.eastmoney.com");
    }

    @Test
    @DisplayName("字段顺序为日期/开/收/高/低，成交量手→股，百分比转小数")
    void parsesFieldsAndUnits() {
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
        assertThat(first.volume()).isEqualTo(42_178_600L);
        assertThat(first.amount()).isEqualTo(322_000_000.00);
        assertThat(first.pctChg()).isNull();
        assertThat(first.turnoverRate()).isCloseTo(0.0014, within(1e-9));
        assertThat(first.preClose()).isNull();
        assertThat(first.source()).isEqualTo("EASTMONEY_KLINE");
        assertThat(first.adjType()).isEqualTo(DailyBar.ADJ_QFQ);

        assertThat(bars.get(1).preClose()).isEqualTo(7.560);
        assertThat(bars.get(1).pctChg()).isCloseTo(-0.0185, within(1e-9));
    }

    @Test
    @DisplayName("data 为空 / klines 缺失：返回空列表，不抛异常")
    void returnsEmptyWhenNoData() {
        given(httpClient.get(anyString(), any())).willReturn("{\"rc\":0,\"data\":null}");

        assertThat(provider.fetchDailyBars("600000.SH",
                LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30))).isEmpty();
    }

    @Test
    @DisplayName("非法 instrumentId：立即拒绝，不发请求")
    void rejectsInvalidInstrumentId() {
        assertThatThrownBy(() -> provider.fetchDailyBars("600000",
                LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30)))
                .isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("非法 instrumentId");
    }

    @Test
    @DisplayName("返回日期超出请求区间：整批拒绝")
    void rejectsOutOfRangeDate() {
        given(httpClient.get(anyString(), any())).willReturn(SAMPLE);

        assertThatThrownBy(() -> provider.fetchDailyBars("600000.SH",
                LocalDate.of(2024, 9, 3), LocalDate.of(2024, 9, 4)))
                .isInstanceOf(DataIntegrityException.class)
                .hasMessageContaining("请求区间外");
    }

    @Test
    @DisplayName("市场标识：沪市为 1，深市/北交所为 0")
    void buildsMarketSecid() {
        given(httpClient.get(anyString(), any())).willReturn("{\"rc\":0,\"data\":null}");

        provider.fetchDailyBars("000001.SZ", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));
        provider.fetchDailyBars("920000.BJ", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));

        org.mockito.Mockito.verify(httpClient)
                .get(org.mockito.ArgumentMatchers.contains("secid=0.000001"), any());
        org.mockito.Mockito.verify(httpClient)
                .get(org.mockito.ArgumentMatchers.contains("secid=0.920000"), any());
    }
}
