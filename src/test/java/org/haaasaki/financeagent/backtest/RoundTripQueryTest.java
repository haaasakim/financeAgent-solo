package org.haaasaki.financeagent.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 往返记录搜索条件测试：关键词、买入日与卖出日范围（闭区间）与空值语义。
 */
class RoundTripQueryTest {

    private final RoundTrip closed = new RoundTrip("000001.SZ", "000001", "平安银行",
            LocalDate.of(2024, 1, 2), 10.0, 1000L, 10000.0, 5.0,
            LocalDate.of(2024, 1, 5), 11.0, 11000.0, 10.0, 15.0, 985.0, 0.09, 3);
    private final RoundTrip open = new RoundTrip("600519.SH", "600519", "贵州茅台",
            LocalDate.of(2024, 2, 1), 1700.0, 100L, 170000.0, 51.0,
            null, null, null, null, 51.0, null, null, 10);

    @Test
    @DisplayName("空条件：全部匹配")
    void emptyMatchesAll() {
        assertThat(RoundTripQuery.EMPTY.isEmpty()).isTrue();
        assertThat(RoundTripQuery.EMPTY.matches(closed)).isTrue();
        assertThat(RoundTripQuery.EMPTY.matches(open)).isTrue();
    }

    @Test
    @DisplayName("关键词：代码或名称包含即命中，忽略大小写与首尾空白")
    void keywordMatchesCodeOrName() {
        assertThat(query("000001", null, null, null, null).matches(closed)).isTrue();
        assertThat(query("平安", null, null, null, null).matches(closed)).isTrue();
        assertThat(query(" 平安 ", null, null, null, null).matches(closed)).isTrue();
        assertThat(query("600519", null, null, null, null).matches(closed)).isFalse();
        assertThat(query("茅台", null, null, null, null).matches(open)).isTrue();
        assertThat(query("   ", null, null, null, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("买入日范围：闭区间，越界不命中")
    void filtersByBuyDateRange() {
        assertThat(query(null, LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2),
                null, null).matches(closed)).isTrue();
        assertThat(query(null, LocalDate.of(2024, 1, 3), null, null, null)
                .matches(closed)).isFalse();
        assertThat(query(null, null, LocalDate.of(2024, 1, 1), null, null)
                .matches(closed)).isFalse();
    }

    @Test
    @DisplayName("卖出日范围：持有中不命中，闭区间含边界")
    void filtersBySellDateRange() {
        assertThat(query(null, null, null, LocalDate.of(2024, 1, 5),
                LocalDate.of(2024, 1, 5)).matches(closed)).isTrue();
        assertThat(query(null, null, null, LocalDate.of(2024, 1, 6), null)
                .matches(closed)).isFalse();
        assertThat(query(null, null, null, null, LocalDate.of(2024, 1, 4))
                .matches(closed)).isFalse();
        assertThat(query(null, null, null, LocalDate.of(2024, 1, 1), null)
                .matches(open)).isFalse();
    }

    @Test
    @DisplayName("组合条件：关键词与日期同时满足才命中")
    void combinesConditions() {
        assertThat(query("000001", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31)).matches(closed)).isTrue();
        assertThat(query("000001", LocalDate.of(2024, 2, 1), null, null, null)
                .matches(closed)).isFalse();
    }

    private RoundTripQuery query(String keyword, LocalDate buyFrom, LocalDate buyTo,
                                 LocalDate sellFrom, LocalDate sellTo) {
        return new RoundTripQuery(keyword, buyFrom, buyTo, sellFrom, sellTo);
    }
}
