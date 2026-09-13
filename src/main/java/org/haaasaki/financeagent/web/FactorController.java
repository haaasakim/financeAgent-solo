package org.haaasaki.financeagent.web;

import java.util.List;

import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.market.FactorQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 因子验证入口：页面与 JSON 接口。
 *
 * <p>M3 的因子是收盘后预计算的宽表，本控制器只做查询展示，不做计算。
 * 页面路由 {@code /market/factors}，JSON 路由 {@code /api/factors/{instrumentId}}。
 */
@Controller
public class FactorController {

    private static final List<Integer> LIMIT_OPTIONS = List.of(30, 60, 120, 250);

    private final FactorQueryService factorQueryService;

    public FactorController(FactorQueryService factorQueryService) {
        this.factorQueryService = factorQueryService;
    }

    @GetMapping("/market/factors")
    public String factors(@RequestParam(defaultValue = "000001.SZ") String instrumentId,
                          @RequestParam(defaultValue = "60") int limit,
                          Model model) {
        model.addAttribute("view", factorQueryService.findRecent(instrumentId, limit));
        model.addAttribute("instrumentId", instrumentId);
        model.addAttribute("limit", FactorQueryService.normalizeLimit(limit));
        model.addAttribute("limitOptions", LIMIT_OPTIONS);
        return "market/factors";
    }

    /** 原始因子行，供脚本对拍；按交易日倒序。 */
    @GetMapping("/api/factors/{instrumentId}")
    @ResponseBody
    public List<FactorDaily> api(@PathVariable String instrumentId,
                                 @RequestParam(defaultValue = "60") int limit) {
        return factorQueryService.findRecent(instrumentId, limit).rows();
    }
}
