package org.haaasaki.financeagent.data.provider.sina;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.haaasaki.financeagent.data.model.InstrumentSnapshot;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 新浪快照解析测试。
 *
 * <p>重点是单位换算与缺失语义：万元→元、百分数→小数、停牌不落 0。
 * 固定响应样本，不发真实请求。
 */
@ExtendWith(MockitoExtension.class)
class SinaSnapshotProviderTest {

    @Mock
    private ThrottledHttpClient httpClient;

    private SinaSnapshotProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SinaSnapshotProvider(httpClient, new ObjectMapper());
    }

    @Test
    @DisplayName("正常返回：字段顺序与单位换算正确（万元→元、百分数→小数）")
    void parsesAndConvertsUnits() {
        given(httpClient.get(anyString(), eq(StandardCharsets.UTF_8))).willReturn("""
                [{"symbol":"sh600000","code":"600000","name":"浦发银行",
                  "trade":"9.260","changepercent":1.2,"volume":123456,"amount":9876543,
                  "per":4.8,"pb":0.42,"mktcap":284500.0,"nmc":280000.0,"turnoverratio":0.42},
                 {"symbol":"sz000001","code":"000001","name":"平安银行",
                  "trade":"11.350","changepercent":-0.53,"volume":98765,"amount":111222333,
                  "per":5.2,"pb":0.65,"mktcap":2201000.0,"nmc":2100000.0,"turnoverratio":0.65}]
                """);

        List<InstrumentSnapshot> snapshots = provider.fetchStocks();

        assertThat(snapshots).hasSize(2);
        InstrumentSnapshot first = snapshots.get(0);
        assertThat(first.symbol()).isEqualTo("sh600000");
        assertThat(first.code()).isEqualTo("600000");
        assertThat(first.name()).isEqualTo("浦发银行");
        assertThat(first.lastPrice()).isEqualTo(9.26);
        assertThat(first.pctChg()).isCloseTo(0.012, within(1e-9));
        assertThat(first.volume()).isEqualTo(123456L);
        assertThat(first.amount()).isEqualTo(9_876_543.0);
        assertThat(first.peTtm()).isEqualTo(4.8);
        assertThat(first.pb()).isEqualTo(0.42);
        // mktcap 原始单位是万元：284500 万 = 2,845,000,000 元
        assertThat(first.totalMv()).isEqualTo(2_845_000_000.0);
        assertThat(first.circMv()).isEqualTo(2_800_000_000.0);
        // turnoverratio 原始为百分数数值：0.42 → 0.0042
        assertThat(first.turnoverRate()).isCloseTo(0.0042, within(1e-9));

        InstrumentSnapshot second = snapshots.get(1);
        assertThat(second.pctChg()).isCloseTo(-0.0053, within(1e-9));
    }

    @Test
    @DisplayName("停牌（trade=0）：价格类字段为 null，不落 0；市值仍换算")
    void suspendedKeepsMissing() {
        given(httpClient.get(anyString(), eq(StandardCharsets.UTF_8))).willReturn("""
                [{"symbol":"sh600000","code":"600000","name":"浦发银行",
                  "trade":"0.000","changepercent":0,"volume":0,"amount":0,
                  "per":0,"pb":0,"mktcap":284500.0,"nmc":280000.0,"turnoverratio":0}]
                """);

        List<InstrumentSnapshot> snapshots = provider.fetchStocks();

        assertThat(snapshots).hasSize(1);
        InstrumentSnapshot snapshot = snapshots.get(0);
        assertThat(snapshot.lastPrice()).isNull();
        assertThat(snapshot.pctChg()).isNull();
        assertThat(snapshot.volume()).isNull();
        assertThat(snapshot.amount()).isNull();
        assertThat(snapshot.turnoverRate()).isNull();
        assertThat(snapshot.peTtm()).isNull();
        assertThat(snapshot.pb()).isNull();
        assertThat(snapshot.totalMv()).isEqualTo(2_845_000_000.0);
    }

    @Test
    @DisplayName("缺少 code/symbol 的行被跳过，非法数值视为缺失")
    void skipsRowsWithoutIdentity() {
        given(httpClient.get(anyString(), eq(StandardCharsets.UTF_8))).willReturn("""
                [{"symbol":"sh600000","name":"缺代码","trade":"9.26","mktcap":1,"nmc":1},
                 {"symbol":"sh600001","code":"600001","name":"正常","trade":"abc","mktcap":1,"nmc":1}]
                """);

        List<InstrumentSnapshot> snapshots = provider.fetchStocks();

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).code()).isEqualTo("600001");
        assertThat(snapshots.get(0).lastPrice()).isNull();
    }

    @Test
    @DisplayName("空响应返回空列表，不抛异常")
    void emptyResponseReturnsEmptyList() {
        given(httpClient.get(anyString(), eq(StandardCharsets.UTF_8))).willReturn("[]");

        assertThat(provider.fetchStocks()).isEmpty();
        assertThat(provider.name()).isEqualTo("SINA_MARKET_CENTER");
    }
}
