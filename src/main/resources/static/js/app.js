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
    initBacktestCharts();
    bindHistoryBack();
  });

  /* HTMX 换入回测结果后重新初始化图表（onLoad 对初始加载不触发，由 DOMContentLoaded 覆盖）。 */
  if (window.htmx) {
    htmx.onLoad(function () {
      initBacktestCharts();
      bindHistoryBack();
    });
  }

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

  /*
    回测页由 HTMX 局部替换结果，不会为提交动作新增浏览器历史记录。
    有同源来源页时优先返回来源页；直接打开页面或来源不可用时，保留
    anchor 的 /screener 作为无 JS / 无历史记录的安全回退。
  */
  function bindHistoryBack() {
    document.querySelectorAll('[data-history-back]').forEach(function (el) {
      if (el.dataset.historyBackBound === 'true') {
        return;
      }
      el.dataset.historyBackBound = 'true';
      el.addEventListener('click', function (event) {
        var sameOriginReferrer = false;
        if (document.referrer) {
          try {
            sameOriginReferrer = new URL(document.referrer, window.location.href).origin
              === window.location.origin;
          } catch (ignored) {
            sameOriginReferrer = false;
          }
        }
        if (sameOriginReferrer && window.history.length > 1) {
          event.preventDefault();
          window.history.back();
        }
      });
    });
  }

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

  /*
    回测结果图表：净值（策略 vs 基准）与回撤。数据由结果片段注入 window.__BT__。
    重复初始化时先销毁旧实例，避免 HTMX 换入结果后残留。
  */
  function initBacktestCharts() {
    var el = document.getElementById('bt-nav-chart');
    if (!el || typeof echarts === 'undefined') {
      return;
    }
    var data = window.__BT__;
    if (!data || !data.dates || data.dates.length === 0) {
      return;
    }

    if (charts.btNav) {
      charts.btNav.dispose();
      charts.btNav = null;
    }
    if (charts.btDd) {
      charts.btDd.dispose();
      charts.btDd = null;
    }
    if (charts.btPosition) {
      charts.btPosition.dispose();
      charts.btPosition = null;
    }

    var primary = cssVar('--primary') || '#185FA5';
    var text3 = cssVar('--text-3') || '#888780';
    var down = cssVar('--down') || '#1D9E75';
    var grid = cssVar('--border') || '#E5E5E1';

    charts.btNav = echarts.init(el);
    charts.btNav.setOption({
      animation: false,
      tooltip: { trigger: 'axis' },
      legend: { data: ['策略', '基准'], right: 10, top: 4, textStyle: { color: text3, fontSize: 11 } },
      grid: { left: 64, right: 20, top: 28, bottom: 28 },
      xAxis: {
        type: 'category',
        data: data.dates,
        axisLabel: { color: text3, fontSize: 10 },
        axisLine: { lineStyle: { color: grid } }
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: text3, fontSize: 10 },
        splitLine: { lineStyle: { color: grid } }
      },
      series: [
        {
          name: '策略', type: 'line', data: data.nav, symbol: 'none',
          lineStyle: { width: 1.5, color: primary }
        },
        {
          name: '基准', type: 'line', data: data.benchmark, symbol: 'none',
          lineStyle: { width: 1, color: text3 }
        }
      ]
    });

    var ddEl = document.getElementById('bt-dd-chart');
    if (ddEl) {
      charts.btDd = echarts.init(ddEl);
      charts.btDd.setOption({
        animation: false,
        tooltip: { trigger: 'axis', valueFormatter: function (v) { return v + '%'; } },
        grid: { left: 64, right: 20, top: 16, bottom: 28 },
        xAxis: {
          type: 'category',
          data: data.dates,
          axisLabel: { color: text3, fontSize: 10 },
          axisLine: { lineStyle: { color: grid } }
        },
        yAxis: {
          type: 'value',
          max: 0,
          axisLabel: { color: text3, fontSize: 10, formatter: '{value}%' },
          splitLine: { lineStyle: { color: grid } }
        },
        series: [
          {
            name: '回撤', type: 'line', data: data.drawdown, symbol: 'none',
            lineStyle: { width: 1, color: down },
            areaStyle: { color: down, opacity: 0.12 }
          }
        ]
      });
    }

    var posEl = document.getElementById('bt-position-chart');
    if (posEl) {
      charts.btPosition = echarts.init(posEl);
      charts.btPosition.setOption({
        animation: false,
        tooltip: {
          trigger: 'axis',
          valueFormatter: function (v) {
            return v == null ? '--' : (Math.round(v * 10) / 10) + '%';
          }
        },
        grid: { left: 64, right: 20, top: 16, bottom: 28 },
        xAxis: {
          type: 'category',
          data: data.dates,
          axisLabel: { color: text3, fontSize: 10 },
          axisLine: { lineStyle: { color: grid } }
        },
        yAxis: {
          type: 'value',
          min: 0,
          max: 100,
          axisLabel: { color: text3, fontSize: 10, formatter: '{value}%' },
          splitLine: { lineStyle: { color: grid } }
        },
        series: [
          {
            name: '持仓比', type: 'line', data: data.positionRatio, symbol: 'none',
            lineStyle: { width: 1, color: primary },
            areaStyle: { color: primary, opacity: 0.12 }
          }
        ]
      });
    }
  }

  window.initBacktestCharts = initBacktestCharts;
})();

/*
  选股页 Alpine 组件：条件卡片的状态管理与 DSL 编译。
  - factors：服务端下发的可用因子（key/label/kind/unit）
  - initial：初始条件树 {all, any, not}
  布尔因子固定 eq，值 1=是 / 0=否；数值因子由用户选择运算符与阈值。
  评分字段（WI-011）：数值条件可勾选「评分」并设置方向与权重；
  旧策略（无 score 字段）按原口径预置勾选状态，保存后行为不变。
*/
function screenerApp(factors, initial) {
  'use strict';

  var factorList = factors || [];
  var factorMap = {};
  factorList.forEach(function (f) {
    factorMap[f.key] = f;
  });
  var start = initial || {};

  function kindOf(key) {
    return (factorMap[key] || {}).kind || 'NUMBER';
  }

  /* eq 无自然方向，界面默认「越大越好」，由用户自行调整 */
  function defaultDirection(op) {
    return (op === 'lt' || op === 'lte') ? 'lower' : 'higher';
  }

  /* 兼容模式（无 score 字段）：数值 + 方向明确的运算符默认参与评分（WI-005 口径） */
  function legacyScored(op) {
    return op !== 'eq';
  }

  function hydrate(list, scorable) {
    return (list || []).map(function (c) {
      var kind = kindOf(c.factor);
      var condition = {
        factor: c.factor,
        op: kind === 'BOOLEAN' ? 'eq' : (c.op || 'gte'),
        value: c.value == null ? (kind === 'BOOLEAN' ? 1 : 0) : c.value
      };
      if (kind !== 'BOOLEAN' && scorable) {
        condition.score = c.score == null ? legacyScored(condition.op) : !!c.score;
        condition.weight = c.weight == null ? 1 : c.weight;
        condition.direction = c.direction || defaultDirection(condition.op);
      }
      return condition;
    });
  }

  function compile(list, scorable) {
    return list.map(function (c) {
      var kind = kindOf(c.factor);
      var condition = {
        factor: c.factor,
        op: kind === 'BOOLEAN' ? 'eq' : c.op,
        value: Number(c.value)
      };
      if (kind !== 'BOOLEAN' && scorable) {
        condition.score = !!c.score;
        if (c.score) {
          condition.direction = c.direction || defaultDirection(condition.op);
          condition.weight = Number(c.weight) > 0 ? Number(c.weight) : 1;
        }
      }
      return condition;
    });
  }

  return {
    /* 因子目录当前分组标签页（WI-012 后目录变长，改为分组切换） */
    factorTab: '基础',

    sections: [
      { key: 'ALL', tag: 'tag-all', title: '全部满足', note: '所有条件同时成立', scorable: true,
        conditions: hydrate(start.all, true) },
      { key: 'ANY', tag: 'tag-any', title: '任一满足', note: '至少一个条件成立', scorable: true,
        conditions: hydrate(start.any, true) },
      { key: 'NOT', tag: 'tag-not', title: '排除', note: '排除同时满足以下全部条件的标的', scorable: false,
        conditions: hydrate(start.not, false) }
    ],

    kindOf: function (key) {
      return kindOf(key);
    },
    unitOf: function (key) {
      return (factorMap[key] || {}).unit || '';
    },
    addCondition: function (key) {
      if (!key) {
        return;
      }
      var kind = this.kindOf(key);
      var condition = {
        factor: key,
        op: kind === 'BOOLEAN' ? 'eq' : 'gte',
        value: kind === 'BOOLEAN' ? 1 : 0
      };
      if (kind !== 'BOOLEAN') {
        condition.score = true;
        condition.weight = 1;
        condition.direction = defaultDirection(condition.op);
      }
      this.sections[0].conditions.push(condition);
    },
    onFactorChange: function (section, index) {
      var condition = section.conditions[index];
      var kind = this.kindOf(condition.factor);
      condition.op = kind === 'BOOLEAN' ? 'eq' : 'gte';
      condition.value = kind === 'BOOLEAN' ? 1 : 0;
      if (kind === 'BOOLEAN') {
        delete condition.score;
        delete condition.weight;
        delete condition.direction;
      } else if (section.scorable) {
        condition.score = true;
        condition.weight = 1;
        condition.direction = defaultDirection(condition.op);
      }
    },
    /* 运算符变化时方向跟随其默认值；用户可再单独调整 */
    onOpChange: function (condition) {
      condition.direction = defaultDirection(condition.op);
    },
    dslJson: function () {
      return JSON.stringify({
        all: compile(this.sections[0].conditions, true),
        any: compile(this.sections[1].conditions, true),
        not: compile(this.sections[2].conditions, false)
      });
    }
  };
}
