package org.haaasaki.financeagent.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.ScreenerService;
import org.haaasaki.financeagent.screener.ScreenerResult;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.haaasaki.financeagent.screener.factor.FactorDefinition;
import org.haaasaki.financeagent.screener.factor.FactorRegistry;
import org.haaasaki.financeagent.screener.template.PresetTemplates;
import org.haaasaki.financeagent.screener.template.StrategyTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 选股模块控制器：页面、结果片段、策略保存与删除。
 *
 * <p>条件卡片的状态由 Alpine 在浏览器端维护，提交时序列化为 DSL JSON；
 * 服务端只做解析、校验与求值。校验失败返回错误摘要片段，不展示堆栈。
 */
@Controller
public class ScreenerController {

    private static final Logger log = LoggerFactory.getLogger(ScreenerController.class);

    private final ScreenerService screenerService;
    private final FactorRegistry factorRegistry;
    private final PresetTemplates presetTemplates;
    private final ObjectMapper objectMapper;

    public ScreenerController(ScreenerService screenerService,
                              FactorRegistry factorRegistry,
                              PresetTemplates presetTemplates,
                              ObjectMapper objectMapper) {
        this.screenerService = screenerService;
        this.factorRegistry = factorRegistry;
        this.presetTemplates = presetTemplates;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/screener")
    public String page(@RequestParam(required = false) String template,
                       @RequestParam(required = false) Long strategyId,
                       @RequestParam(required = false) String dsl,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        ScreenerDsl initial = ScreenerDsl.empty();
        String sourceLabel = null;

        if (strategyId != null) {
            try {
                Strategy strategy = screenerService.findStrategy(strategyId);
                initial = screenerService.parse(strategy.dsl());
                sourceLabel = "已加载策略：" + strategy.name();
            } catch (BusinessException e) {
                log.warn("加载策略失败 strategyId={}：{}", strategyId, e.getMessage());
                sourceLabel = "策略不存在，已重置条件";
                strategyId = null;
            }
        } else if (template != null) {
            StrategyTemplate preset = presetTemplates.find(template);
            if (preset != null) {
                initial = preset.dsl();
                sourceLabel = "已套用模板：" + preset.name();
            }
        }

        // HTMX 选股结果通过 URL 保存后，浏览器返回 /screener 时恢复条件与结果。
        if (dsl != null && !dsl.isBlank()) {
            try {
                initial = screenerService.parse(dsl);
                sourceLabel = "已恢复上次选股条件";
                addResult(model, initial, asOf, page);
                model.addAttribute("selectedAsOf", asOf);
            } catch (BusinessException e) {
                log.warn("恢复选股结果失败：{}", e.getMessage());
                model.addAttribute("errorMessage", e.getMessage());
            }
        }

        model.addAttribute("factorGroups", factorRegistry.groups());
        model.addAttribute("presets", presetTemplates.all());
        model.addAttribute("strategies", screenerService.listStrategies());
        model.addAttribute("initialDslJson", screenerService.toJson(initial));
        model.addAttribute("availableFactors", availableFactors());
        model.addAttribute("availableFactorsJson", availableFactorsJson());
        model.addAttribute("sourceLabel", sourceLabel);
        model.addAttribute("selectedStrategyId", strategyId);
        if (!model.containsAttribute("selectedAsOf")) {
            model.addAttribute("selectedAsOf", null);
        }
        model.addAttribute("dataStart", screenerService.dataStartDate());
        model.addAttribute("dataEnd", screenerService.dataEndDate());
        return "screener/index";
    }

    @PostMapping("/fragments/screener/results")
    public String results(@RequestParam String dsl,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                          @RequestParam(defaultValue = "1") int page,
                          HttpServletResponse response,
                          Model model) {
        try {
            ScreenerDsl parsed = screenerService.parse(dsl);
            addResult(model, parsed, asOf, page);
            ScreenerResult result = (ScreenerResult) model.getAttribute("result");
            response.setHeader("HX-Push-Url", resultUrl(screenerService.toJson(parsed), asOf,
                    result == null ? Math.max(1, page) : result.page()));
        } catch (BusinessException e) {
            log.warn("选股条件无效：{}", e.getMessage());
            model.addAttribute("errorMessage", e.getMessage());
        }
        return "fragments/screener-results";
    }

    private void addResult(Model model, ScreenerDsl dsl, LocalDate requestedAsOf, int page) {
        if (requestedAsOf == null) {
            model.addAttribute("result", screenerService.screen(dsl, page));
            return;
        }
        LocalDate resolved = screenerService.resolveAsOf(requestedAsOf);
        model.addAttribute("result", screenerService.screenAt(dsl, resolved, page));
        model.addAttribute("asOf", resolved);
        model.addAttribute("asOfLabel", resolved.equals(requestedAsOf)
                ? resolved.toString()
                : resolved + "（已按最近交易日）");
    }

    private String resultUrl(String dsl, LocalDate asOf, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/screener")
                .queryParam("dsl", dsl)
                .queryParam("page", Math.max(1, page));
        if (asOf != null) {
            builder.queryParam("asOf", asOf);
        }
        return builder.build().encode().toUriString();
    }

    @PostMapping("/fragments/screener/strategies")
    public String saveStrategy(@RequestParam String name,
                               @RequestParam(required = false) String description,
                               @RequestParam String dsl,
                               @RequestParam(required = false) Long selectedStrategyId,
                               HttpServletResponse response,
                               Model model) {
        String sourceLabel = selectedStrategyLabel(selectedStrategyId);
        try {
            Strategy saved = screenerService.saveStrategy(name, description, dsl);
            selectedStrategyId = saved.id();
            sourceLabel = "已保存策略：" + saved.name();
            response.setHeader("HX-Replace-Url", "/screener?strategyId=" + saved.id());
            model.addAttribute("notice", sourceLabel);
        } catch (BusinessException e) {
            log.warn("保存策略失败：{}", e.getMessage());
            model.addAttribute("errorMessage", e.getMessage());
        }
        model.addAttribute("strategies", screenerService.listStrategies());
        model.addAttribute("selectedStrategyId", selectedStrategyId);
        model.addAttribute("sourceLabel", sourceLabel);
        model.addAttribute("fragmentUpdate", true);
        return "fragments/screener-strategies";
    }

    @PostMapping("/fragments/screener/strategies/{id}/delete")
    public String deleteStrategy(@PathVariable long id,
                                 @RequestParam(required = false) Long selectedStrategyId,
                                 HttpServletResponse response,
                                 Model model) {
        screenerService.deleteStrategy(id);
        boolean deletedCurrent = selectedStrategyId != null && selectedStrategyId == id;
        if (deletedCurrent) {
            selectedStrategyId = null;
            response.setHeader("HX-Replace-Url", "/screener");
            model.addAttribute("notice", "已删除策略；当前条件仍保留，但尚未保存");
            model.addAttribute("sourceLabel", "当前条件尚未保存");
        } else {
            model.addAttribute("notice", "已删除策略");
            model.addAttribute("sourceLabel", selectedStrategyLabel(selectedStrategyId));
        }
        model.addAttribute("strategies", screenerService.listStrategies());
        model.addAttribute("selectedStrategyId", selectedStrategyId);
        model.addAttribute("fragmentUpdate", true);
        return "fragments/screener-strategies";
    }

    private String selectedStrategyLabel(Long selectedStrategyId) {
        if (selectedStrategyId == null) {
            return null;
        }
        try {
            return "已加载策略：" + screenerService.findStrategy(selectedStrategyId).name();
        } catch (BusinessException e) {
            return null;
        }
    }

    /** 页面条件行的因子下拉选项（灰显因子不参与）。 */
    private List<FactorDefinition> availableFactors() {
        return factorRegistry.all().stream()
                .filter(FactorDefinition::available)
                .toList();
    }

    /** 页面 Alpine 组件所需的可用因子清单（灰显因子不参与）。 */
    private String availableFactorsJson() {
        List<Map<String, Object>> items = availableFactors().stream()
                .map(definition -> Map.<String, Object>of(
                        "key", definition.key(),
                        "label", definition.label(),
                        "kind", definition.kind().name(),
                        "unit", definition.unit()))
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("因子清单一序列化失败", e);
        }
    }
}
