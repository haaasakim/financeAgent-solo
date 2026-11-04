package org.haaasaki.financeagent.data.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 行情源回退链：失败切换、空数据切换、全部失败抛出最后一个错误。 */
@ExtendWith(MockitoExtension.class)
class FallbackQuoteProviderTest {

    private static final String ID = "600000.SH";
    private static final LocalDate START = LocalDate.of(2024, 9, 1);
    private static final LocalDate END = LocalDate.of(2024, 9, 30);

    @Mock
    private QuoteProvider primary;
    @Mock
    private QuoteProvider secondary;

    @Test
    @DisplayName("主源成功：直接返回，不调用备源")
    void returnsPrimaryWhenSuccessful() {
        List<DailyBar> bars = List.of(bar());
        given(primary.fetchDailyBars(ID, START, END)).willReturn(bars);
        FallbackQuoteProvider provider = new FallbackQuoteProvider(List.of(primary, secondary));

        assertThat(provider.fetchDailyBars(ID, START, END)).isEqualTo(bars);
        verify(secondary, never()).fetchDailyBars(any(), any(), any());
    }

    @Test
    @DisplayName("主源网络失败：切换到备源")
    void fallsBackWhenPrimaryThrows() {
        List<DailyBar> bars = List.of(bar());
        given(primary.fetchDailyBars(ID, START, END)).willThrow(new DataSyncException("501"));
        given(secondary.fetchDailyBars(ID, START, END)).willReturn(bars);
        FallbackQuoteProvider provider = new FallbackQuoteProvider(List.of(primary, secondary));

        assertThat(provider.fetchDailyBars(ID, START, END)).isEqualTo(bars);
    }

    @Test
    @DisplayName("主源数据校验失败：同样切换备源")
    void fallsBackWhenPrimaryIntegrityFails() {
        List<DailyBar> bars = List.of(bar());
        given(primary.fetchDailyBars(ID, START, END))
                .willThrow(new DataIntegrityException("恰好 640 条"));
        given(secondary.fetchDailyBars(ID, START, END)).willReturn(bars);
        FallbackQuoteProvider provider = new FallbackQuoteProvider(List.of(primary, secondary));

        assertThat(provider.fetchDailyBars(ID, START, END)).isEqualTo(bars);
    }

    @Test
    @DisplayName("主源返回空：视为该源无数据，直接返回空，不再打备源")
    void emptyPrimaryIsReturnedWithoutSecondary() {
        given(primary.fetchDailyBars(ID, START, END)).willReturn(List.of());
        FallbackQuoteProvider provider = new FallbackQuoteProvider(List.of(primary, secondary));

        assertThat(provider.fetchDailyBars(ID, START, END)).isEmpty();
        verify(secondary, never()).fetchDailyBars(any(), any(), any());
    }

    @Test
    @DisplayName("全部失败：抛出最后一个错误")
    void throwsLastErrorWhenAllFail() {
        given(primary.fetchDailyBars(ID, START, END)).willThrow(new DataSyncException("primary down"));
        given(secondary.fetchDailyBars(ID, START, END)).willThrow(new DataSyncException("secondary down"));
        FallbackQuoteProvider provider = new FallbackQuoteProvider(List.of(primary, secondary));

        assertThatThrownBy(() -> provider.fetchDailyBars(ID, START, END))
                .isInstanceOf(DataSyncException.class)
                .hasMessageContaining("secondary down");
    }

    @Test
    @DisplayName("空回退链：构造时拒绝")
    void rejectsEmptyChain() {
        assertThatThrownBy(() -> new FallbackQuoteProvider(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DailyBar bar() {
        return new DailyBar(ID, LocalDate.of(2024, 9, 2), 7.5, 7.6, 7.4, 7.55,
                null, 1000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
