package org.haaasaki.financeagent.web;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.ScreenerService;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.haaasaki.financeagent.screener.factor.FactorDefinition;
import org.haaasaki.financeagent.screener.factor.FactorRegistry;
import org.haaasaki.financeagent.screener.template.PresetTemplates;
import org.haaasaki.financeagent.screener.template.StrategyTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
            }
        } else if (template != null) {
            StrategyTemplate preset = presetTemplates.find(template);
            if (preset != null) {
                initial = preset.dsl();
                sourceLabel = "已套用模板：" + preset.name();
            }
        }

        model.addAttribute("factorGroups", factorRegistry.groups());
        model.addAttribute("presets", presetTemplates.all());
        model.addAttribute("strategies", screenerService.listStrategies());
        model.addAttribute("initialDslJson", screenerService.toJson(initial));
        model.addAttribute("availableFactors", availableFactors());
        model.addAttribute("availableFactorsJson", availableFactorsJson());
        model.addAttribute("sourceLabel", sourceLabel);
        return "screener/index";
    }

    @PostMapping("/fragments/screener/results")
    public String results(@RequestParam String dsl,
                          @RequestParam(defaultValue = "1") int page,
                          Model model) {
        try {
            model.addAttribute("result",
                    screenerService.screen(screenerService.parse(dsl), page));
        } catch (BusinessException e) {
            log.warn("选股条件无效：{}", e.getMessage());
            model.addAttribute("errorMessage", e.getMessage());
        }
        return "fragments/screener-results";
    }

    @PostMapping("/fragments/screener/strategies")
    public String saveStrategy(@RequestParam String name,
                               @RequestParam(required = false) String description,
                               @RequestParam String dsl,
                               Model model) {
        try {
            screenerService.saveStrategy(name, description, dsl);
            model.addAttribute("notice", "已保存策略：" + name.trim());
        } catch (BusinessException e) {
            log.warn("保存策略失败：{}", e.getMessage());
            model.addAttribute("errorMessage", e.getMessage());
        }
        model.addAttribute("strategies", screenerService.listStrategies());
        return "fragments/screener-strategies";
    }

    @PostMapping("/fragments/screener/strategies/{id}/delete")
    public String deleteStrategy(@PathVariable long id, Model model) {
        screenerService.deleteStrategy(id);
        model.addAttribute("notice", "已删除策略");
        model.addAttribute("strategies", screenerService.listStrategies());
        return "fragments/screener-strategies";
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
