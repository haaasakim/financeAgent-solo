package org.haaasaki.financeagent.data.provider.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.provider.ValuationPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 东财估值响应解析测试：用固定样本覆盖正常 / 空数据 / 错误 / 畸形 JSON 四种情况，不发真实请求。
 */
class EastmoneyValuationProviderTest {

    private final EastmoneyValuationProvider provider =
            new EastmoneyValuationProvider(null, new ObjectMapper());

    @Test
    @DisplayName("正常返回：条数、日期切片与非正值置空")
    void parsesNormalPage() {
        String body = """
                {"version":null,"result":{"pages":5,"count":2,"data":[
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-11 00:00:00","PE_TTM":6.02,"PB_MRQ":0.409},
                  {"SECURITY_CODE":"600000","TRADE_DATE":"2026-09-10 00:00:00","PE_TTM":-3.5,"PB_MRQ":null}
                ]},"success":true,"message":"ok","code":0}
                """;

        EastmoneyValuationProvider.Page page = provider.parsePage(body);

        assertThat(page.count()).isEqualTo(2);
        List<ValuationPoint> points = page.points();
        assertThat(points).hasSize(2);
        assertThat(points.get(0).code()).isEqualTo("600000");
        assertThat(points.get(0).tradeDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(points.get(0).peTtm()).isEqualTo(6.02);
        assertThat(points.get(0).pbMrq()).isEqualTo(0.409);
        assertThat(points.get(1).peTtm()).isNull();
        assertThat(points.get(1).pbMrq()).isNull();
    }

    @Test
    @DisplayName("ETF 等无数据返回 code=9201：视为空数据而非失败")
    void treatsEmptyDataAsEmpty() {
        String body = """
                {"version":null,"result":null,"success":false,"message":"返回数据为空","code":9201}
                """;

        assertThat(provider.parsePage(body)).isNull();
    }

    @Test
    @DisplayName("其他失败响应与畸形 JSON 抛数据同步异常")
    void rejectsFailures() {
        assertThatThrownBy(() -> provider.parsePage(
                "{\"success\":false,\"message\":\"参数错误\",\"code\":1001}"))
                .isInstanceOf(DataSyncException.class)
                .hasMessageContaining("参数错误");

        assertThatThrownBy(() -> provider.parsePage("<html>bad</html>"))
                .isInstanceOf(DataSyncException.class)
                .hasMessageContaining("解析");
    }
}
