package org.haaasaki.financeagent.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.haaasaki.financeagent.data.model.DailyBar;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactorCalculatorTest {

    private static final String ID = "600000.SH";

    @Test
    @DisplayName("空输入返回空列表，而不是 null")
    void emptyInput() {
        assertThat(FactorCalculator.calculate(List.of())).isEmpty();
    }

    @Test
    @DisplayName("行数与日K一致，回看不足的列为 null；MACD 无回看不足语义")
    void warmupColumnsAreNull() {
        List<FactorDaily> rows = FactorCalculator.calculate(List.of(
                bar(LocalDate.of(2024, 1, 1), 10.0),
                bar(LocalDate.of(2024, 1, 5), 11.0)));

        assertThat(rows).hasSize(2);
        FactorDaily last = rows.get(1);
        assertThat(last.instrumentId()).isEqualTo(ID);
        assertThat(last.tradeDate()).isEqualTo(LocalDate.of(2024, 1, 5));
        assertThat(last.ma5()).isNull();
        assertThat(last.rsi6()).isNull();
        assertThat(last.return20()).isNull();
        assertThat(last.volatility20()).isNull();
        assertThat(last.pctFrom52wHigh()).isNull();
        assertThat(last.volumeRatio5()).isNull();
        assertThat(last.volumeRatio20()).isNull();
        assertThat(last.volChg20()).isNull();
        assertThat(last.atr20()).isNull();
        assertThat(last.amplitude20()).isNull();
        assertThat(last.downsideVol20()).isNull();
        assertThat(last.pctFrom52wLow()).isNull();
        assertThat(last.newHigh60()).isNull();
        assertThat(last.newHigh120()).isNull();
        assertThat(last.macdDif()).isNotNull();
        assertThat(last.macdHist()).isNotNull();
    }

    @Test
    @DisplayName("v2 量价与位置因子：量比 / ATR / 振幅 / 新高窗口，负收益缺失时下行波动为 null")
    void computesVolumeAndPositionColumns() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        double prevClose = 10.0;
        for (int i = 0; i < 60; i++) {
            double close = i == 59 ? 11.0 : 10.0;
            bars.add(bar(date, close, close + 0.5, close - 0.5,
                    i == 0 ? null : prevClose, i + 1));
            prevClose = close;
            date = date.plusDays(1);
        }

        FactorDaily last = FactorCalculator.calculate(bars).get(59);

        assertThat(last.volumeRatio5()).isCloseTo(60.0 / 57.0, within(1e-12));
        assertThat(last.volumeRatio20()).isNotNull();
        assertThat(last.volChg20()).isNotNull();
        // TR：19 根 1.0 + 末根跳空 1.5 → ATR = 1.025，除以收盘 11
        assertThat(last.atr20()).isCloseTo(1.025 / 11.0, within(1e-12));
        assertThat(last.amplitude20()).isCloseTo(0.1, within(1e-12));
        assertThat(last.downsideVol20()).isNull();
        assertThat(last.newHigh60()).isCloseTo(1.0, within(1e-12));
        assertThat(last.newHigh120()).isNull();
        assertThat(last.pctFrom52wLow()).isNull();
    }

    @Test
    @DisplayName("停牌缺口不按日历对齐：均线按 bar 序列滚动")
    void gapsFollowBarSequence() {
        // 日期跨度 10 天只有 5 根 bar；MA5 应为 5 根收盘的均值 3.0
        List<FactorDaily> rows = FactorCalculator.calculate(List.of(
                bar(LocalDate.of(2024, 1, 1), 1.0),
                bar(LocalDate.of(2024, 1, 5), 2.0),
                bar(LocalDate.of(2024, 1, 6), 3.0),
                bar(LocalDate.of(2024, 1, 10), 4.0),
                bar(LocalDate.of(2024, 1, 11), 5.0)));

        assertThat(rows.get(4).ma5()).isCloseTo(3.0, within(1e-12));
        assertThat(rows.get(3).ma5()).isNull();
    }

    @Test
    @DisplayName("22 根 bar 的 20 日收益与 250 根横盘的距高点")
    void computesReturnsAndHighPosition() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 22; i++) {
            // 前 21 根 10 元，最后一根 12 元 → return20 = 0.2
            double close = i == 21 ? 12.0 : 10.0;
            bars.add(bar(date, close));
            date = date.plusDays(1);
        }
        List<FactorDaily> rows = FactorCalculator.calculate(bars);
        assertThat(rows.get(21).return20()).isCloseTo(0.2, within(1e-12));

        List<DailyBar> flat = new ArrayList<>();
        date = LocalDate.of(2023, 1, 1);
        for (int i = 0; i < 250; i++) {
            flat.add(bar(date, 10.0));
            date = date.plusDays(1);
        }
        List<FactorDaily> flatRows = FactorCalculator.calculate(flat);
        assertThat(flatRows.get(249).pctFrom52wHigh()).isCloseTo(0.0, within(1e-12));
        assertThat(flatRows.get(248).pctFrom52wHigh()).isNull();
    }

    private DailyBar bar(LocalDate date, double close) {
        return new DailyBar(ID, date, close, close, close, close,
                null, 1_000L, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }

    private DailyBar bar(LocalDate date, double close, double high, double low,
                         Double preClose, long volume) {
        return new DailyBar(ID, date, close, high, low, close,
                preClose, volume, null, null, null, DailyBar.ADJ_QFQ, "TEST");
    }
}
