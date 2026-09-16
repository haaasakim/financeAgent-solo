package org.haaasaki.financeagent.screener;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.data.mapper.BacktestDailyMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTaskMapper;
import org.haaasaki.financeagent.data.mapper.BacktestTradeMapper;
import org.haaasaki.financeagent.data.mapper.ScreenerQueryMapper;
import org.haaasaki.financeagent.data.mapper.StrategyMapper;
import org.haaasaki.financeagent.data.model.FactorValue;
import org.haaasaki.financeagent.data.model.ScreenerRow;
import org.haaasaki.financeagent.data.model.SqlCondition;
import org.haaasaki.financeagent.data.model.Strategy;
import org.haaasaki.financeagent.screener.dsl.CompareOp;
import org.haaasaki.financeagent.screener.dsl.Condition;
import org.haaasaki.financeagent.screener.dsl.DslValidator;
import org.haaasaki.financeagent.screener.dsl.ScoreDirection;
import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;
import org.haaasaki.financeagent.screener.factor.FactorDefinition;
import org.haaasaki.financeagent.screener.factor.FactorKind;
import org.haaasaki.financeagent.screener.factor.FactorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final BacktestTaskMapper backtestTaskMapper;
    private final BacktestDailyMapper backtestDailyMapper;
    private final BacktestTradeMapper backtestTradeMapper;
    private final FactorRegistry factorRegistry;
    private final ObjectMapper objectMapper;

    public ScreenerService(ScreenerQueryMapper screenerQueryMapper,
                           StrategyMapper strategyMapper,
                           BacktestTaskMapper backtestTaskMapper,
                           BacktestDailyMapper backtestDailyMapper,
                           BacktestTradeMapper backtestTradeMapper,
                           FactorRegistry factorRegistry,
                           ObjectMapper objectMapper) {
        this.screenerQueryMapper = screenerQueryMapper;
        this.strategyMapper = strategyMapper;
        this.backtestTaskMapper = backtestTaskMapper;
        this.backtestDailyMapper = backtestDailyMapper;
        this.backtestTradeMapper = backtestTradeMapper;
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

        List<SqlCondition> all = resolve(dsl.all(), false);
        List<SqlCondition> any = resolve(dsl.any(), false);
        List<SqlCondition> not = resolve(dsl.not(), false);

        long total = screenerQueryMapper.countCandidates(all, any, not);
        int currentPage = Math.max(1, page);
        if (total > ScreenerResult.MAX_MATCHES) {
            return new ScreenerResult(List.of(), total, 1, ScreenerResult.DEFAULT_SIZE);
        }
        if (total == 0) {
            return new ScreenerResult(List.of(), 0, currentPage, ScreenerResult.DEFAULT_SIZE);
        }

        List<ScreenerRow> ranked = rank(screenerQueryMapper.findAllCandidates(all, any, not), dsl, null);
        int offset = (currentPage - 1) * ScreenerResult.DEFAULT_SIZE;
        List<ScreenerRow> rows = offset >= ranked.size()
                ? List.of()
                : List.copyOf(ranked.subList(offset,
                        Math.min(ranked.size(), offset + ScreenerResult.DEFAULT_SIZE)));
        return new ScreenerResult(rows, total, currentPage, ScreenerResult.DEFAULT_SIZE);
    }

    /**
     * 历史截面回看：以 {@code asOf} 交易日为截面，命中、评分与排序口径与回测一致
     * （不可回测因子由校验拒绝），返回匹配总数与当前页结果（每页 50）。
     */
    public ScreenerResult screenAt(ScreenerDsl dsl, LocalDate asOf, int page) {
        DslValidator.validateBacktest(dsl, factorRegistry);

        List<SqlCondition> all = resolve(dsl.all(), true);
        List<SqlCondition> any = resolve(dsl.any(), true);
        List<SqlCondition> not = resolve(dsl.not(), true);

        long total = screenerQueryMapper.countCandidatesAt(asOf, all, any, not);
        int currentPage = Math.max(1, page);
        if (total > ScreenerResult.MAX_MATCHES) {
            return new ScreenerResult(List.of(), total, 1, ScreenerResult.DEFAULT_SIZE);
        }
        if (total == 0) {
            return new ScreenerResult(List.of(), 0, currentPage, ScreenerResult.DEFAULT_SIZE);
        }

        List<ScreenerRow> ranked = rank(
                screenerQueryMapper.findAllCandidatesAt(asOf, all, any, not), dsl, asOf);
        int offset = (currentPage - 1) * ScreenerResult.DEFAULT_SIZE;
        List<ScreenerRow> rows = offset >= ranked.size()
                ? List.of()
                : List.copyOf(ranked.subList(offset,
                        Math.min(ranked.size(), offset + ScreenerResult.DEFAULT_SIZE)));
        return new ScreenerResult(rows, total, currentPage, ScreenerResult.DEFAULT_SIZE);
    }

    /**
     * 回看日期解析：所选日期无因子行（周末、假日、停牌日或数据范围外）时，
     * 向前最多 15 个自然日找最近的交易日；找不到抛用户可见异常。
     */
    public LocalDate resolveAsOf(LocalDate requested) {
        LocalDate date = requested;
        for (int i = 0; i <= 15; i++) {
            if (screenerQueryMapper.countFactorRowsAt(date) > 0) {
                return date;
            }
            date = date.minusDays(1);
        }
        throw new BusinessException("所选日期附近没有数据，请选择本地行情范围内的交易日");
    }

    /** 本地因子数据的最早交易日；空库返回 null。 */
    public LocalDate dataStartDate() {
        return screenerQueryMapper.minFactorDate();
    }

    /** 本地因子数据的最新交易日；空库返回 null。 */
    public LocalDate dataEndDate() {
        return screenerQueryMapper.maxFactorDate();
    }

    /**
     * 回测前的策略校验：拒绝无历史序列的因子（估值 / 财务占位）。
     */
    public void validateBacktest(ScreenerDsl dsl) {
        DslValidator.validateBacktest(dsl, factorRegistry);
    }

    /**
     * 历史截面求值（回测用）：以 {@code asOf} 交易日为截面，按同一 DSL 与评分口径
     * 返回前 {@code limit} 名。估值等无历史序列的因子由校验直接拒绝。
     */
    public List<ScreenerRow> evaluateAt(ScreenerDsl dsl, LocalDate asOf, int limit) {
        DslValidator.validateBacktest(dsl, factorRegistry);

        List<SqlCondition> all = resolve(dsl.all(), true);
        List<SqlCondition> any = resolve(dsl.any(), true);
        List<SqlCondition> not = resolve(dsl.not(), true);

        List<ScreenerRow> ranked = rank(
                screenerQueryMapper.findAllCandidatesAt(asOf, all, any, not), dsl, asOf);
        return ranked.size() <= limit ? ranked : List.copyOf(ranked.subList(0, limit));
    }

    /** 白名单解析：因子 key → 注册表表达式（按最新截面或历史截面），运算符 → 枚举符号。 */
    private List<SqlCondition> resolve(List<Condition> conditions, boolean backtest) {
        return conditions.stream()
                .map(condition -> {
                    FactorDefinition definition = factorRegistry.find(condition.factor());
                    String expression = backtest
                            ? definition.backtestExpression()
                            : definition.expression();
                    return new SqlCondition(expression, condition.op().sql(), condition.value());
                })
                .toList();
    }

    /** 评分降序；无评分最后；同分按 instrument_id 升序。asOf 非空时用历史截面取因子值。 */
    private List<ScreenerRow> rank(List<ScreenerRow> matched, ScreenerDsl dsl, LocalDate asOf) {
        List<ScoringCondition> conditions = scoringConditions(dsl, asOf != null);
        List<ScreenerRow> scored = conditions.isEmpty()
                ? new ArrayList<>(matched)
                : score(matched, conditions, asOf);
        scored.sort(Comparator
                .comparingDouble((ScreenerRow row) -> row.score() == null ? -1.0 : row.score())
                .reversed()
                .thenComparing(ScreenerRow::instrumentId));
        return scored;
    }

    /**
     * 参与评分的条件（WI-011）：
     * 显式模式（任一条件携带 {@code score} 字段）下只取 {@code score=true} 的数值条件；
     * 兼容模式（旧策略 JSON）沿用 WI-005 口径：数值 + 方向明确的运算符自动参与、权重 1。
     */
    private List<ScoringCondition> scoringConditions(ScreenerDsl dsl, boolean backtest) {
        boolean explicit = isExplicitScoring(dsl);
        List<ScoringCondition> result = new ArrayList<>();
        addScoringConditions(result, dsl.all(), false, backtest, explicit);
        addScoringConditions(result, dsl.any(), true, backtest, explicit);
        return result;
    }

    private boolean isExplicitScoring(ScreenerDsl dsl) {
        return Stream.of(dsl.all(), dsl.any(), dsl.not())
                .flatMap(List::stream)
                .anyMatch(condition -> condition.score() != null);
    }

    private void addScoringConditions(List<ScoringCondition> target,
                                      List<Condition> conditions, boolean anyGroup,
                                      boolean backtest, boolean explicit) {
        for (Condition condition : conditions) {
            FactorDefinition definition = factorRegistry.find(condition.factor());
            if (definition.kind() != FactorKind.NUMBER
                    || condition.value() == null || condition.op() == null) {
                continue;
            }
            boolean higherBetter;
            if (explicit) {
                if (!Boolean.TRUE.equals(condition.score())) {
                    continue;
                }
                ScoreDirection direction = condition.direction() != null
                        ? condition.direction() : ScoreDirection.defaultFor(condition.op());
                if (direction == null) {
                    // eq 未指定方向已由校验拒绝；此处防御性跳过
                    continue;
                }
                higherBetter = direction == ScoreDirection.HIGHER;
            } else {
                switch (condition.op()) {
                    case GT, GTE -> higherBetter = true;
                    case LT, LTE -> higherBetter = false;
                    default -> {
                        continue;
                    }
                }
            }
            String expression = backtest
                    ? definition.backtestExpression()
                    : definition.expression();
            double weight = explicit && condition.weight() != null
                    ? condition.weight() : 1.0;
            target.add(new ScoringCondition(definition.key(), expression,
                    condition.op(), condition.value(), higherBetter, weight, anyGroup));
        }
    }

    /**
     * 相对百分位评分：每个数值条件在命中集内取值，按方向折算 0~1 后平均 ×100。
     * any 组条件仅在该行确实满足时参与；无参与条件为 NULL。
     */
    private List<ScreenerRow> score(List<ScreenerRow> matched,
                                    List<ScoringCondition> conditions, LocalDate asOf) {
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
            List<FactorValue> all = asOf == null
                    ? screenerQueryMapper.findFactorValues(entry.getValue())
                    : screenerQueryMapper.findFactorValuesAt(asOf, entry.getValue());
            List<FactorValue> values = all.stream()
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
            double weightSum = 0;
            for (ScoringCondition condition : conditions) {
                Double value = valuesByFactor.get(condition.factor()).get(row.instrumentId());
                if (value == null) {
                    continue;
                }
                if (condition.anyGroup() && !satisfies(condition, value)) {
                    continue;
                }
                sum += condition.weight() * percentile(distributions.get(condition.factor()), value,
                        condition.higherBetter());
                weightSum += condition.weight();
            }
            // 保留 1 位小数（与页面展示一致）：避免同分因浮点末位差被拆开
            scored.add(row.withScore(weightSum == 0 ? null
                    : Math.round(sum / weightSum * 1000.0) / 10.0));
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
    public Strategy saveStrategy(String name, String description, String dslJson) {
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
        return strategyMapper.findByName(trimmedName)
                .orElseThrow(() -> new IllegalStateException("策略保存后无法读取"));
    }

    /**
     * 删除策略并同步删除其全部回测记录（任务、日度序列、订单流水），返回删除任务数。
     * 有 PENDING / RUNNING 任务时拒绝，避免执行中的任务写入半截数据。
     */
    @Transactional
    public int deleteStrategy(long id) {
        long active = backtestTaskMapper.countActiveByStrategy(id);
        if (active > 0) {
            throw new BusinessException(
                    "该策略有 " + active + " 个回测任务未结束，请等任务完成后再删除");
        }
        backtestTradeMapper.deleteByStrategy(id);
        backtestDailyMapper.deleteByStrategy(id);
        int tasks = backtestTaskMapper.deleteByStrategy(id);
        strategyMapper.deleteById(id);
        return tasks;
    }

    /**
     * 评分条件（服务端解析，表达式只来自注册表白名单）。
     *
     * @param anyGroup true 表示仅在该行满足条件时参与评分（any 组语义）
     * @param weight   评分权重（1 = 等权；WI-011 显式模式可自定义）
     */
    private record ScoringCondition(
            String factor,
            String expression,
            CompareOp op,
            double threshold,
            boolean higherBetter,
            double weight,
            boolean anyGroup) {
    }
}
