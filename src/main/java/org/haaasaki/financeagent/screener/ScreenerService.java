package org.haaasaki.financeagent.screener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.ScreenerQueryMapper;
import org.haaasaki.financeagent.data.mapper.StrategyMapper;
import org.haaasaki.financeagent.data.model.FactorValue;
import org.haaasaki.financeagent.data.model.ScreenerRow;
import org.haaasaki.financeagent.data.model.SqlCondition;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.dsl.CompareOp;
import org.haaasaki.financeagent.screener.dsl.Condition;
import org.haaasaki.financeagent.screener.dsl.DslValidator;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.haaasaki.financeagent.screener.factor.FactorDefinition;
import org.haaasaki.financeagent.screener.factor.FactorKind;
import org.haaasaki.financeagent.screener.factor.FactorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 选股服务：条件树求值 + 评分排序 + 策略持久化。
 *
 * <p>求值只读本地表；用户输入经因子注册表与运算符白名单解析后才进入 SQL
 * （表达式与运算符来自白名单，阈值走参数绑定）。
 * 命中的前 5,000 只在内存中按相对百分位评分、降序排列后分页
 * （口径见 docs/work-items/WI-005-选股评分与排序.md）。
 */
@Service
public class ScreenerService {

    private static final Logger log = LoggerFactory.getLogger(ScreenerService.class);
    private static final int MAX_NAME_LENGTH = 50;

    private final ScreenerQueryMapper screenerQueryMapper;
    private final StrategyMapper strategyMapper;
    private final FactorRegistry factorRegistry;
    private final ObjectMapper objectMapper;

    public ScreenerService(ScreenerQueryMapper screenerQueryMapper,
                           StrategyMapper strategyMapper,
                           FactorRegistry factorRegistry,
                           ObjectMapper objectMapper) {
        this.screenerQueryMapper = screenerQueryMapper;
        this.strategyMapper = strategyMapper;
        this.factorRegistry = factorRegistry;
        this.objectMapper = objectMapper;
    }

    /** 解析条件树 JSON；格式错误抛用户可见异常。 */
    public ScreenerDsl parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException("条件树为空");
        }
        try {
            return objectMapper.readValue(json, ScreenerDsl.class);
        } catch (JsonProcessingException e) {
            log.warn("条件树解析失败：{}", e.getOriginalMessage());
            throw new BusinessException("条件树格式错误");
        }
    }

    public String toJson(ScreenerDsl dsl) {
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("条件树序列化失败", e);
        }
    }

    /** 求值：返回匹配总数与当前页结果（每页 50，按评分降序）。 */
    public ScreenerResult screen(ScreenerDsl dsl, int page) {
        DslValidator.validate(dsl, factorRegistry);

        List<SqlCondition> all = resolve(dsl.all());
        List<SqlCondition> any = resolve(dsl.any());
        List<SqlCondition> not = resolve(dsl.not());

        long total = screenerQueryMapper.countCandidates(all, any, not);
        int currentPage = Math.max(1, page);
        if (total > ScreenerResult.MAX_MATCHES) {
            return new ScreenerResult(List.of(), total, 1, ScreenerResult.DEFAULT_SIZE);
        }
        if (total == 0) {
            return new ScreenerResult(List.of(), 0, currentPage, ScreenerResult.DEFAULT_SIZE);
        }

        List<ScreenerRow> ranked = rank(screenerQueryMapper.findAllCandidates(all, any, not), dsl);
        int offset = (currentPage - 1) * ScreenerResult.DEFAULT_SIZE;
        List<ScreenerRow> rows = offset >= ranked.size()
                ? List.of()
                : List.copyOf(ranked.subList(offset,
                        Math.min(ranked.size(), offset + ScreenerResult.DEFAULT_SIZE)));
        return new ScreenerResult(rows, total, currentPage, ScreenerResult.DEFAULT_SIZE);
    }

    /** 白名单解析：因子 key → 注册表表达式，运算符 → 枚举符号。 */
    private List<SqlCondition> resolve(List<Condition> conditions) {
        return conditions.stream()
                .map(condition -> {
                    FactorDefinition definition = factorRegistry.find(condition.factor());
                    return new SqlCondition(
                            definition.expression(),
                            condition.op().sql(),
                            condition.value());
                })
                .toList();
    }

    /** 评分降序；无评分最后；同分按 instrument_id 升序。 */
    private List<ScreenerRow> rank(List<ScreenerRow> matched, ScreenerDsl dsl) {
        List<ScoringCondition> conditions = scoringConditions(dsl);
        List<ScreenerRow> scored = conditions.isEmpty()
                ? new ArrayList<>(matched)
                : score(matched, conditions);
        scored.sort(Comparator
                .comparingDouble((ScreenerRow row) -> row.score() == null ? -1.0 : row.score())
                .reversed()
                .thenComparing(ScreenerRow::instrumentId));
        return scored;
    }

    /** 参与评分的条件：all / any 组中的数值因子 + 方向明确（gt/gte/lt/lte）的条件。 */
    private List<ScoringCondition> scoringConditions(ScreenerDsl dsl) {
        List<ScoringCondition> result = new ArrayList<>();
        addScoringConditions(result, dsl.all(), false);
        addScoringConditions(result, dsl.any(), true);
        return result;
    }

    private void addScoringConditions(List<ScoringCondition> target,
                                      List<Condition> conditions, boolean anyGroup) {
        for (Condition condition : conditions) {
            FactorDefinition definition = factorRegistry.find(condition.factor());
            if (definition.kind() != FactorKind.NUMBER
                    || condition.value() == null || condition.op() == null) {
                continue;
            }
            boolean higherBetter;
            switch (condition.op()) {
                case GT, GTE -> higherBetter = true;
                case LT, LTE -> higherBetter = false;
                default -> {
                    continue;
                }
            }
            target.add(new ScoringCondition(definition.key(), definition.expression(),
                    condition.op(), condition.value(), higherBetter, anyGroup));
        }
    }

    /**
     * 相对百分位评分：每个数值条件在命中集内取值，按方向折算 0~1 后平均 ×100。
     * any 组条件仅在该行确实满足时参与；无参与条件为 NULL。
     */
    private List<ScreenerRow> score(List<ScreenerRow> matched, List<ScoringCondition> conditions) {
        Set<String> matchedIds = matched.stream()
                .map(ScreenerRow::instrumentId)
                .collect(Collectors.toSet());
        Map<String, String> expressionByFactor = new LinkedHashMap<>();
        for (ScoringCondition condition : conditions) {
            expressionByFactor.putIfAbsent(condition.factor(), condition.expression());
        }
        Map<String, Map<String, Double>> valuesByFactor = new LinkedHashMap<>();
        Map<String, double[]> distributions = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : expressionByFactor.entrySet()) {
            List<FactorValue> values = screenerQueryMapper.findFactorValues(entry.getValue()).stream()
                    .filter(value -> value.value() != null
                            && matchedIds.contains(value.instrumentId()))
                    .toList();
            valuesByFactor.put(entry.getKey(), values.stream().collect(
                    Collectors.toMap(FactorValue::instrumentId, FactorValue::value)));
            distributions.put(entry.getKey(), values.stream()
                    .mapToDouble(FactorValue::value)
                    .sorted()
                    .toArray());
        }

        List<ScreenerRow> scored = new ArrayList<>(matched.size());
        for (ScreenerRow row : matched) {
            double sum = 0;
            int count = 0;
            for (ScoringCondition condition : conditions) {
                Double value = valuesByFactor.get(condition.factor()).get(row.instrumentId());
                if (value == null) {
                    continue;
                }
                if (condition.anyGroup() && !satisfies(condition, value)) {
                    continue;
                }
                sum += percentile(distributions.get(condition.factor()), value,
                        condition.higherBetter());
                count++;
            }
            // 保留 1 位小数（与页面展示一致）：避免同分因浮点末位差被拆开
            scored.add(row.withScore(count == 0 ? null
                    : Math.round(sum / count * 1000.0) / 10.0));
        }
        return scored;
    }

    private boolean satisfies(ScoringCondition condition, double value) {
        return switch (condition.op()) {
            case GT -> value > condition.threshold();
            case GTE -> value >= condition.threshold();
            case LT -> value < condition.threshold();
            case LTE -> value <= condition.threshold();
            case EQ -> false;
        };
    }

    /** 分位：越大越好用小于自身的占比；越小越好用大于自身的占比；单点得满分。 */
    private double percentile(double[] sortedValues, double value, boolean higherBetter) {
        int n = sortedValues.length;
        if (n <= 1) {
            return 1.0;
        }
        if (higherBetter) {
            return (double) lowerBound(sortedValues, value) / (n - 1);
        }
        return (double) (n - upperBound(sortedValues, value)) / (n - 1);
    }

    private int lowerBound(double[] values, double target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (values[mid] < target) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int upperBound(double[] values, double target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (values[mid] <= target) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    public List<Strategy> listStrategies() {
        return strategyMapper.findAll();
    }

    public Strategy findStrategy(long id) {
        return strategyMapper.findById(id)
                .orElseThrow(() -> new BusinessException("策略不存在"));
    }

    /** 保存策略：先校验条件树可执行；同名覆盖更新。 */
    public void saveStrategy(String name, String description, String dslJson) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("策略名不能为空");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("策略名不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        ScreenerDsl dsl = parse(dslJson);
        DslValidator.validate(dsl, factorRegistry);
        strategyMapper.upsert(trimmedName, description, dslJson);
    }

    public void deleteStrategy(long id) {
        strategyMapper.deleteById(id);
    }

    /**
     * 评分条件（服务端解析，表达式只来自注册表白名单）。
     *
     * @param anyGroup true 表示仅在该行满足条件时参与评分（any 组语义）
     */
    private record ScoringCondition(
            String factor,
            String expression,
            CompareOp op,
            double threshold,
            boolean higherBetter,
            boolean anyGroup) {
    }
}
