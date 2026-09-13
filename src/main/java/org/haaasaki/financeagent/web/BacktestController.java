package org.haaasaki.financeagent.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.backtest.BacktestRequest;
import org.haaasaki.financeagent.backtest.BacktestResultView;
import org.haaasaki.financeagent.backtest.BacktestService;
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
                       Model model) {
        model.addAttribute("active", "backtest");
        model.addAttribute("strategies", backtestService.strategies());
        model.addAttribute("benchmarks", backtestService.benchmarks());
        model.addAttribute("recentTasks", backtestService.recentTasks());
        model.addAttribute("selectedStrategyId", strategyId);

        LocalDate dataThrough = backtestService.dataThrough().orElse(null);
        model.addAttribute("defaultEnd", dataThrough);
        model.addAttribute("defaultStart", dataThrough == null ? null : dataThrough.minusYears(3));

        if (taskId != null) {
            try {
                attachResult(model, backtestService.result(taskId, 1));
            } catch (BusinessException e) {
                model.addAttribute("loadError", e.getMessage());
            }
        }
        return "backtest/index";
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
                      @RequestParam String benchmarkId,
                      Model model) {
        try {
            long taskId = backtestService.createTask(new BacktestRequest(strategyId, startDate, endDate,
                    frequency, topN, initialCapital, commissionRatePct, stampTaxPct,
                    slippageBps, benchmarkId));
            model.addAttribute("task", backtestService.task(taskId));
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

    /** 把结果与图表数据放进模型，供模板渲染与 app.js 初始化。 */
    private void attachResult(Model model, BacktestResultView result) {
        List<BacktestDaily> daily = result.daily();
        model.addAttribute("result", result);
        model.addAttribute("dates", daily.stream()
                .map(row -> row.tradeDate().toString()).toList());
        model.addAttribute("navSeries", daily.stream().map(BacktestDaily::nav).toList());
        model.addAttribute("benchmarkSeries", daily.stream().map(BacktestDaily::benchmarkNav).toList());
        model.addAttribute("drawdownSeries", daily.stream()
                .map(row -> row.drawdown() * 100).toList());
        if (!daily.isEmpty()) {
            BacktestDaily last = daily.get(daily.size() - 1);
            model.addAttribute("excessReturn",
                    (last.nav() - last.benchmarkNav()) / result.task().initialCapital());
        }
    }
}
