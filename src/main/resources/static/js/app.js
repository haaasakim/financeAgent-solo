/*
  financeAgent 前端逻辑。
  - K 线图初始化（ECharts），均线在浏览器端按收盘价计算
  - HTMX 错误响应允许替换，以展示错误片段与重试按钮
  数据由模板通过 window.__KLINE__ 注入（th:inline="javascript"）。
*/
(function () {
  'use strict';

  var charts = {};

  document.addEventListener('DOMContentLoaded', function () {
    initKline();
  });

  window.addEventListener('resize', function () {
    Object.keys(charts).forEach(function (key) {
      if (charts[key]) {
        charts[key].resize();
      }
    });
  });

  /* HTMX 默认不替换 4xx/5xx 响应；这里允许替换，服务端返回的是带重试按钮的错误片段。 */
  document.addEventListener('htmx:beforeSwap', function (event) {
    if (event.detail.xhr && event.detail.xhr.status >= 400) {
      event.detail.shouldSwap = true;
      event.detail.isError = false;
    }
  });

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  /* 移动平均：前 n-1 个点为 null（ECharts 会断开），不使用 0 填充。 */
  function movingAverage(values, n) {
    return values.map(function (_, i) {
      if (i < n - 1) {
        return null;
      }
      var sum = 0;
      for (var j = i - n + 1; j <= i; j++) {
        sum += values[j];
      }
      return +(sum / n).toFixed(2);
    });
  }

  function initKline() {
    var el = document.getElementById('kline-chart');
    if (!el || typeof echarts === 'undefined') {
      return;
    }
    var data = window.__KLINE__;
    if (!data || !data.dates || data.dates.length === 0) {
      return;
    }

    if (charts.kline) {
      charts.kline.dispose();
    }

    var up = cssVar('--up');
    var down = cssVar('--down');
    var grid = cssVar('--border-l');
    var axis = cssVar('--text-3');
    var primary = cssVar('--primary');
    var ma5Color = '#EF9F27';
    var ma10Color = '#7B61FF';
    var ma20Color = primary;
    var ma60Color = '#4C9A8F';

    var closes = data.candles.map(function (c) {
      return c[1];
    });
    var volumeColors = data.candles.map(function (c) {
      return c[1] >= c[0] ? 'rgba(217,54,62,.30)' : 'rgba(29,158,117,.30)';
    });

    var chart = echarts.init(el);
    chart.setOption({
      animation: false,
      grid: [
        { left: 64, right: 20, top: 28, height: '60%' },
        { left: 64, right: 20, top: '76%', height: '13%' }
      ],
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        backgroundColor: '#fff',
        borderColor: grid,
        textStyle: { color: cssVar('--text'), fontSize: 12 }
      },
      legend: {
        data: ['MA5', 'MA10', 'MA20', 'MA60'],
        right: 0,
        top: 0,
        itemWidth: 14,
        itemHeight: 2,
        textStyle: { color: cssVar('--text-2'), fontSize: 11 }
      },
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      xAxis: [
        {
          type: 'category', data: data.dates, gridIndex: 0,
          axisLine: { lineStyle: { color: grid } },
          axisLabel: { show: false }, axisTick: { show: false }
        },
        {
          type: 'category', data: data.dates, gridIndex: 1,
          axisLine: { lineStyle: { color: grid } },
          axisLabel: { color: axis, fontSize: 11 }, axisTick: { show: false }
        }
      ],
      yAxis: [
        {
          scale: true, gridIndex: 0,
          splitLine: { lineStyle: { color: grid } },
          axisLabel: { color: axis, fontSize: 11 }, axisLine: { show: false }
        },
        {
          scale: true, gridIndex: 1,
          splitLine: { show: false },
          axisLabel: { show: false }, axisLine: { show: false }
        }
      ],
      series: [
        {
          name: 'K', type: 'candlestick', xAxisIndex: 0, yAxisIndex: 0,
          data: data.candles,
          itemStyle: { color: up, color0: down, borderColor: up, borderColor0: down }
        },
        { name: 'MA5', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: movingAverage(closes, 5), symbol: 'none', lineStyle: { width: 1, color: ma5Color } },
        { name: 'MA10', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: movingAverage(closes, 10), symbol: 'none', lineStyle: { width: 1, color: ma10Color } },
        { name: 'MA20', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: movingAverage(closes, 20), symbol: 'none', lineStyle: { width: 1, color: ma20Color } },
        { name: 'MA60', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: movingAverage(closes, 60), symbol: 'none', lineStyle: { width: 1, color: ma60Color } },
        {
          name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1,
          data: data.volumes,
          itemStyle: { color: function (params) { return volumeColors[params.dataIndex]; } },
          barWidth: '60%'
        }
      ],
      dataZoom: [
        { type: 'inside', xAxisIndex: [0, 1] },
        {
          type: 'slider', xAxisIndex: [0, 1], height: 14, bottom: 4,
          borderColor: grid,
          fillerColor: 'rgba(24,95,165,.08)',
          handleStyle: { color: primary },
          textStyle: { color: axis, fontSize: 10 }
        }
      ]
    });

    charts.kline = chart;
  }
})();
