package org.haaasaki.financeagent.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 往返记录排序白名单测试：方向、空值恒最后、未知键回退。
 */
class RoundTripSortTest {

    private final RoundTrip a = new RoundTrip("000002.SZ", "000002", "B", LocalDate.of(2024, 1, 2),
            20.0, 500L, 10000.0, LocalDate.of(2024, 1, 5), 22.0, 11000.0, 900.0, 0.09, 3);
    private final RoundTrip b = new RoundTrip("000001.SZ", "000001", "A", LocalDate.of(2024, 1, 3),
            10.0, 1000L, 20000.0, LocalDate.of(2024, 1, 4), 11.0, 22000.0, 1900.0, 0.095, 1);
    private final RoundTrip open = new RoundTrip("000003.SZ", "000003", "C", LocalDate.of(2024, 1, 4),
            30.0, 100L, 3000.0, null, null, null, null, null, 2);

    @Test
    @DisplayName("代码升 / 降序")
    void sortsByCode() {
        assertThat(sorted(RoundTripSort.CODE, false)).extracting(RoundTrip::code)
                .containsExactly("000001", "000002", "000003");
        assertThat(sorted(RoundTripSort.CODE, true)).extracting(RoundTrip::code)
                .containsExactly("000003", "000002", "000001");
    }

    @Test
    @DisplayName("盈亏排序：持有中（空值）恒在最后，升序与降序都不翻转")
    void sortsByPnlNullsLast() {
        assertThat(sorted(RoundTripSort.PNL, true)).extracting(RoundTrip::code)
                .containsExactly("000001", "000002", "000003");
        assertThat(sorted(RoundTripSort.PNL, false)).extracting(RoundTrip::code)
                .containsExactly("000002", "000001", "000003");
    }

    @Test
    @DisplayName("买入金额降序")
    void sortsByBuyAmount() {
        assertThat(sorted(RoundTripSort.BUY_AMOUNT, true)).extracting(RoundTrip::code)
                .containsExactly("000001", "000002", "000003");
    }

    @Test
    @DisplayName("同列同值时按买入日、代码升序兜底，保证分页稳定")
    void stableTieBreak() {
        RoundTrip c1 = new RoundTrip("000005.SZ", "000005", "E", LocalDate.of(2024, 1, 2),
                10.0, 100L, 5000.0, LocalDate.of(2024, 1, 9), 11.0, 5500.0, 500.0, 0.1, 7);
        RoundTrip c2 = new RoundTrip("000004.SZ", "000004", "D", LocalDate.of(2024, 1, 2),
                10.0, 100L, 5000.0, LocalDate.of(2024, 1, 8), 11.0, 5500.0, 500.0, 0.1, 6);
        List<RoundTrip> list = new ArrayList<>(List.of(c1, c2));
        list.sort(RoundTripSort.BUY_AMOUNT.comparator(true));
        assertThat(list).extracting(RoundTrip::code).containsExactly("000004", "000005");
    }

    @Test
    @DisplayName("未知排序键回退默认买入日升序")
    void fallsBackToDefault() {
        assertThat(RoundTripSort.fromKey("nope")).isEqualTo(RoundTripSort.BUY_DATE);
        assertThat(RoundTripSort.fromKey(null)).isEqualTo(RoundTripSort.BUY_DATE);
    }

    private List<RoundTrip> sorted(RoundTripSort sort, boolean desc) {
        List<RoundTrip> list = new ArrayList<>(List.of(a, b, open));
        list.sort(sort.comparator(desc));
        return list;
    }
}
