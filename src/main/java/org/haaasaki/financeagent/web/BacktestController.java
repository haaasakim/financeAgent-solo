package org.haaasaki.financeagent.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.haaasaki.financeagent.backtest.BacktestRequest;
import org.haaasaki.financeagent.backtest.BacktestResultView;
import org.haaasaki.financeagent.backtest.BacktestService;
import org.haaasaki.financeagent.backtest.BacktestTaskSort;
import org.haaasaki.financeagent.backtest.BacktestTaskView;
import org.haaasaki.financeagent.backtest.RoundTripQuery;
import org.haaasaki.financeagent.backtest.RoundTripSort;
import org.haaasaki.financeagent.backtest.RoundTripView;
import org.haaasaki.financeagent.backtest.engine.BacktestParams;
import org.haaasaki.financeagent.backtest.engine.RebalanceFrequency;
import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.model.BacktestDaily;
import org.haaasaki.financeagent.data.model.BacktestTask;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 回测模块控制器：参数页、任务提交、进度轮询与结果片段。
 *
 * <p>回测在后台线程执行，页面每 2s 轮询进度；成功后同一片段路由返回结果，
 * 结果片段不带轮询属性，轮询自然停止（见 docs/FRONTEND_GUIDE.md 第 4 节）。
 */
@Controller
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @GetMapping("/backtest")
    public String page(@RequestParam(required = false) Long strategyId,
                       @RequestParam(required = false) Long taskId,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) String dir,
                       Model model) {
        model.addAttribute("active", "backtest");
        model.addAttribute("strategies", backtestService.strategies());
        model.addAttribute("benchmarks", backtestService.benchmarks());
        model.addAttribute("taskView", taskView(page, sort, dir));
        model.addAttribute("selectedTaskId", taskId);

        addFormDefaults(model, strategyId);

        if (taskId != null) {
            try {
                // 历史或运行中回测：参数回显后按任务状态恢复结果、进度或失败态。
                backtestService.params(taskId).ifPresent(params -> applyFormDefaults(model, params));
                BacktestTask task = backtestService.task(taskId);
                if (BacktestTask.STATUS_SUCCESS.equals(task.status())) {
                    attachResult(model, backtestService.result(taskId, 1));
                } else {
                    model.addAttribute("currentTask", task);
                    model.addAttribute("task", task);
                }
            } catch (BusinessException e) {
                model.addAttribute("loadError", e.getMessage());
            }
        }
        return "backtest/index";
    }

    /** 回测记录局部刷新：分页与排序保留在同一片段内（HTMX）。 */
    @GetMapping("/fragments/backtest/tasks")
    public String tasks(@RequestParam(defaultValue = "1") int page,
                        @RequestParam(required = false) String sort,
                        @RequestParam(required = false) String dir,
                        @RequestParam(required = false) Long taskId,
                        HttpServletResponse response,
                        Model model) {
        BacktestTaskView taskView = taskView(page, sort, dir);
        model.addAttribute("taskView", taskView);
        model.addAttribute("selectedTaskId", taskId);
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/backtest");
        if (taskId != null) {
            url.queryParam("taskId", taskId);
        }
        response.setHeader("HX-Push-Url", url
                .queryParam("page", taskView.page())
                .queryParam("sort", taskView.sort().key())
                .queryParam("dir", taskView.desc() ? "desc" : "asc")
                .build().encode().toUriString());
        return "fragments/backtest-recent-tasks";
    }

    /** 解析排序参数：未知键回退编号；dir 缺省按降序（最新 / 最高在前）。 */
    private BacktestTaskView taskView(int page, String sortKey, String dir) {
        BacktestTaskSort sort = BacktestTaskSort.fromKey(sortKey);
        boolean desc = dir == null || dir.isBlank() || !"asc".equalsIgnoreCase(dir);
        return backtestService.recentTasks(page, sort, desc);
    }

    /** 表单默认值：新回测为最近 3 年 / 每月 / Top20 / 100 万 / 默认费率与沪深300。 */
    private void addFormDefaults(Model model, Long strategyId) {
        LocalDate dataThrough = backtestService.dataThrough().orElse(null);
        model.addAttribute("defaultEnd", dataThrough);
        model.addAttribute("defaultStart", dataThrough == null ? null : dataThrough.minusYears(3));
        model.addAttribute("selectedStrategyId", strategyId);
        model.addAttribute("selectedFrequency", RebalanceFrequency.MONTHLY.name());
        model.addAttribute("selectedTopN", 20);
        model.addAttribute("defaultInitialCapital", "1000000");
        model.addAttribute("defaultCommissionRatePct", "0.03");
        model.addAttribute("defaultStampTaxPct", "0.05");
        model.addAttribute("defaultSlippageBps", "5");
        model.addAttribute("defaultStopLossPct", "12");
        model.addAttribute("defaultTimeStopDays", 0);
        model.addAttribute("selectedBenchmarkId", "000300.SH");
    }

    /** 回显历史任务参数；金额与费率按页面单位（元 / % / bps）展示。 */
    private void applyFormDefaults(Model model, BacktestParams params) {
        model.addAttribute("defaultStart", params.startDate());
        model.addAttribute("defaultEnd", params.endDate());
        model.addAttribute("selectedStrategyId", params.strategyId());
        model.addAttribute("selectedFrequency", params.frequency().name());
        model.addAttribute("selectedTopN", params.topN());
        model.addAttribute("defaultInitialCapital", plain(params.initialCapital()));
        model.addAttribute("defaultCommissionRatePct", plain(params.commissionRate().movePointRight(2)));
        model.addAttribute("defaultStampTaxPct", plain(params.stampTaxRate().movePointRight(2)));
        model.addAttribute("defaultSlippageBps", plain(params.slippage().movePointRight(4)));
        model.addAttribute("defaultStopLossPct", params.stopLossRate() == null
                ? "0" : plain(params.stopLossRate().movePointRight(2)));
        model.addAttribute("defaultTimeStopDays", params.timeStopDays());
        model.addAttribute("selectedBenchmarkId", params.benchmarkId());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    @GetMapping("/backtest/{id}")
    public String detail(@PathVariable long id) {
        return "redirect:/backtest?taskId=" + id;
    }

    @PostMapping("/fragments/backtest/run")
    public String run(@RequestParam long strategyId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                      @RequestParam(defaultValue = "MONTHLY") String frequency,
                      @RequestParam(defaultValue = "20") int topN,
                      @RequestParam BigDecimal initialCapital,
                      @RequestParam(defaultValue = "0.03") BigDecimal commissionRatePct,
                      @RequestParam(defaultValue = "0.05") BigDecimal stampTaxPct,
                      @RequestParam(defaultValue = "5") BigDecimal slippageBps,
                      @RequestParam(defaultValue = "0") BigDecimal stopLossPct,
                      @RequestParam(defaultValue = "0") int timeStopDays,
                      @RequestParam String benchmarkId,
                      HttpServletResponse response,
                      Model model) {
        try {
            long taskId = backtestService.createTask(new BacktestRequest(strategyId, startDate, endDate,
                    frequency, topN, initialCapital, commissionRatePct, stampTaxPct,
                    slippageBps, stopLossPct, timeStopDays, benchmarkId));
            model.addAttribute("task", backtestService.task(taskId));
            response.setHeader("HX-Push-Url", "/backtest?taskId=" + taskId);
            return "fragments/backtest-progress";
        } catch (BusinessException e) {
            model.addAttribute("message", e.getMessage());
            return "fragments/backtest-error";
        }
    }

    @GetMapping("/fragments/backtest/{id}/status")
    public String status(@PathVariable long id, Model model) {
        BacktestTask task = backtestService.task(id);
        if (BacktestTask.STATUS_SUCCESS.equals(task.status())) {
            attachResult(model, backtestService.result(id, 1));
            return "fragments/backtest-result";
        }
        model.addAttribute("task", task);
        return BacktestTask.STATUS_FAILED.equals(task.status())
                ? "fragments/backtest-failed"
                : "fragments/backtest-progress";
    }

    @GetMapping("/fragments/backtest/{id}/trades")
    public String trades(@PathVariable long id,
                         @RequestParam(defaultValue = "1") int page,
                         Model model) {
        model.addAttribute("result", backtestService.result(id, page));
        return "fragments/backtest-trades";
    }

    @GetMapping("/fragments/backtest/{id}/trips")
    public String trips(@PathVariable long id,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(required = false) String sort,
                        @RequestParam(required = false) String dir,
                        @RequestParam(required = false) String keyword,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate buyFrom,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate buyTo,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sellFrom,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sellTo,
                        Model model) {
        model.addAttribute("tripView", backtestService.roundTrips(id, page,
                RoundTripSort.fromKey(sort), "desc".equalsIgnoreCase(dir),
                new RoundTripQuery(keyword, buyFrom, buyTo, sellFrom, sellTo)));
        model.addAttribute("sortFields", RoundTripSort.values());
        return "fragments/backtest-round-trips";
    }

    /** 把结果、往返配对与图表数据放进模型，供模板渲染与 app.js 初始化。 */
    private void attachResult(Model model, BacktestResultView result) {
        List<BacktestDaily> daily = result.daily();
        model.addAttribute("result", result);
        RoundTripView tripView = backtestService.roundTrips(result.task().id(), 1,
                RoundTripSort.DEFAULT, false, RoundTripQuery.EMPTY);
        model.addAttribute("tripView", tripView);
        model.addAttribute("totalFee", tripView.feeTotal());
        model.addAttribute("sortFields", RoundTripSort.values());
        model.addAttribute("dates", daily.stream()
                .map(row -> row.tradeDate().toString()).toList());
        model.addAttribute("navSeries", daily.stream().map(BacktestDaily::nav).toList());
        model.addAttribute("benchmarkSeries", daily.stream().map(BacktestDaily::benchmarkNav).toList());
        model.addAttribute("drawdownSeries", daily.stream()
                .map(row -> row.drawdown() * 100).toList());
        // 持仓比 = 持仓市值 / 净值 × 100（%）；净值为 0 时缺失，曲线断开
        model.addAttribute("positionRatioSeries", daily.stream()
                .map(row -> row.nav() == 0 ? null : row.marketValue() / row.nav() * 100).toList());
        if (!daily.isEmpty()) {
            BacktestDaily last = daily.get(daily.size() - 1);
            model.addAttribute("excessReturn",
                    (last.nav() - last.benchmarkNav()) / result.task().initialCapital());
        }
    }
}
