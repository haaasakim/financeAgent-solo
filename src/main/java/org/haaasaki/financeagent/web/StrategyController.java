package org.haaasaki.financeagent.web;

import java.util.List;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.ScreenerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 独立策略库页面与策略删除入口。 */
@Controller
public class StrategyController {

    private static final Logger log = LoggerFactory.getLogger(StrategyController.class);

    private final ScreenerService screenerService;

    public StrategyController(ScreenerService screenerService) {
        this.screenerService = screenerService;
    }

    @GetMapping("/strategies")
    public String page(@RequestParam(required = false) String keyword, Model model) {
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        List<Strategy> strategies = screenerService.searchStrategies(normalizedKeyword);
        model.addAttribute("strategies", strategies);
        model.addAttribute("keyword", normalizedKeyword == null ? "" : normalizedKeyword);
        model.addAttribute("hasKeyword", normalizedKeyword != null && !normalizedKeyword.isBlank());
        return "strategy/library";
    }

    @PostMapping("/strategies/{id}/delete")
    public String delete(@PathVariable long id,
                         @RequestParam(required = false) String keyword,
                         RedirectAttributes redirectAttributes) {
        try {
            int removedTasks = screenerService.deleteStrategy(id);
            String cascade = removedTasks > 0 ? "，已同步删除 " + removedTasks + " 条回测记录" : "";
            redirectAttributes.addFlashAttribute("notice", "已删除策略" + cascade);
        } catch (BusinessException e) {
            log.warn("策略库删除失败 strategyId={}：{}", id, e.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        if (keyword != null && !keyword.isBlank()) {
            redirectAttributes.addAttribute("keyword", keyword.trim());
        }
        return "redirect:/strategies";
    }
}
