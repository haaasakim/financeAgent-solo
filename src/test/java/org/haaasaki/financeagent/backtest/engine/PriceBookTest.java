package org.haaasaki.financeagent.backtest.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.mapper.DailyBarMapper;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 前复权失真防护测试（WI-013）：失真窗口判定与有效收盘价回退。
 */
class PriceBookTest {

    private static final String ID = "000937.SZ";
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);

    @Test
    @DisplayName("无失真：全程可交易，收盘价取当日")
    void tradableWhenNoBadBar() {
        List<DailyBar> bars = bars(30, 10.0);

        PriceBook book = book(bars);

        assertThat(book.tradableAt(ID, FROM)).isTrue();
        assertThat(book.tradableAt(ID, FROM.plusDays(29))).isTrue();
        assertThat(book.closeAt(ID, FROM.plusDays(10))).isEqualTo(10.0);
    }

    @Test
    @DisplayName("出现过失真 bar：整段不可交易（含首次非正价之前的压缩正价段）")
    void untradableWholeRangeWhenAnyBadBar() {
        List<DailyBar> bars = bars(300, 10.0);
        bars.set(5, bar(FROM.plusDays(5), -1.0));

        PriceBook book = book(bars);

        assertThat(book.tradableAt(ID, FROM.plusDays(4))).isFalse();
        assertThat(book.tradableAt(ID, FROM.plusDays(5))).isFalse();
        assertThat(book.tradableAt(ID, FROM.plusDays(255))).isFalse();
    }

    @Test
    @DisplayName("0 价 bar 同样视为失真：整段不可交易")
    void untradableWhenZeroPriceBar() {
        List<DailyBar> bars = bars(30, 10.0);
        bars.set(29, bar(FROM.plusDays(29), 0.0));

        PriceBook book = book(bars);

        assertThat(book.tradableAt(ID, FROM.plusDays(29))).isFalse();
    }

    @Test
    @DisplayName("盯市价回退：跳过非正收盘，取最近有效价；全无有效价为 null")
    void closeAtSkipsNonPositiveCloses() {
        List<DailyBar> bars = bars(10, 10.0);
        bars.set(8, bar(FROM.plusDays(8), -0.5));
        bars.set(9, bar(FROM.plusDays(9), 0.0));

        PriceBook book = book(bars);

        assertThat(book.closeAt(ID, FROM.plusDays(9))).isEqualTo(10.0);

        List<DailyBar> allBad = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            allBad.add(bar(FROM.plusDays(i), -1.0));
        }
        assertThat(book(allBad).closeAt(ID, FROM.plusDays(2))).isNull();
    }

    private PriceBook book(List<DailyBar> bars) {
        DailyBarMapper mapper = mock(DailyBarMapper.class);
        when(mapper.findRange(eq(ID), any(), any())).thenReturn(bars);
        return new PriceBook(mapper, FROM, FROM.plusDays(400));
    }

    private List<DailyBar> bars(int count, double close) {
        List<DailyBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(bar(FROM.plusDays(i), close));
        }
        return bars;
    }

    private DailyBar bar(LocalDate date, double close) {
        return new DailyBar(ID, date, close, close, close, close, close > 0 ? close : null,
                100_000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
