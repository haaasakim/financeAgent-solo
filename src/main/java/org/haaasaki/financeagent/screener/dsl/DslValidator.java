package org.haaasaki.financeagent.screener.dsl;

import org.haaasaki.financeagent.common.exception.BusinessException;
import org.haaasaki.financeagent.screener.factor.FactorDefinition;
import org.haaasaki.financeagent.screener.factor.FactorKind;
import org.haaasaki.financeagent.screener.factor.FactorRegistry;

/**
 * 条件树校验：因子与运算符必须来自白名单，数值必须有限且不过界。
 *
 * <p>校验失败抛 {@link BusinessException}（用户可见），错误信息面向页面展示。
 */
public final class DslValidator {

    static final int MAX_PER_GROUP = 20;
    static final int MAX_TOTAL = 50;
    static final double MAX_ABS_VALUE = 1_000_000;
    /** 评分权重范围（WI-011）。 */
    static final double MIN_SCORE_WEIGHT = 0.1;
    static final double MAX_SCORE_WEIGHT = 10.0;

    private DslValidator() {
    }

    public static void validate(ScreenerDsl dsl, FactorRegistry registry) {
        int total = dsl.all().size() + dsl.any().size() + dsl.not().size();
        if (total == 0) {
            throw new BusinessException("请至少设置一个条件");
        }
        if (total > MAX_TOTAL) {
            throw new BusinessException("条件总数不能超过 " + MAX_TOTAL + " 个");
        }
        validateGroup("全部满足", dsl.all(), registry, true);
        validateGroup("任一满足", dsl.any(), registry, true);
        validateGroup("排除", dsl.not(), registry, false);
    }

    /**
     * 回测专用校验：在普通校验之后，要求每个因子都有历史截面表达式；
     * 快照类因子（PE-TTM / PB）与财务占位因子没有历史序列，禁止用于回测。
     */
    public static void validateBacktest(ScreenerDsl dsl, FactorRegistry registry) {
        validate(dsl, registry);
        validateBacktestGroup("全部满足", dsl.all(), registry);
        validateBacktestGroup("任一满足", dsl.any(), registry);
        validateBacktestGroup("排除", dsl.not(), registry);
    }

    private static void validateBacktestGroup(String groupLabel, java.util.List<Condition> conditions,
                                              FactorRegistry registry) {
        for (Condition condition : conditions) {
            FactorDefinition definition = registry.find(condition.factor());
            if (definition == null || !definition.backtestable()) {
                String label = definition == null ? condition.factor() : definition.label();
                throw new BusinessException(
                        "因子「" + label + "」无历史数据，暂不可用于回测");
            }
        }
    }

    private static void validateGroup(String groupLabel, java.util.List<Condition> conditions,
                                      FactorRegistry registry, boolean allowScoring) {
        if (conditions.size() > MAX_PER_GROUP) {
            throw new BusinessException(groupLabel + "的条件不能超过 " + MAX_PER_GROUP + " 个");
        }
        for (Condition condition : conditions) {
            validateCondition(groupLabel, condition, registry, allowScoring);
        }
    }

    private static void validateCondition(String groupLabel, Condition condition,
                                          FactorRegistry registry, boolean allowScoring) {
        FactorDefinition definition = registry.find(condition.factor());
        if (definition == null) {
            throw new BusinessException(groupLabel + "存在未知因子：" + condition.factor());
        }
        if (!definition.available()) {
            throw new BusinessException("因子「" + definition.label() + "」暂无数据源，暂不可用");
        }
        if (condition.op() == null) {
            throw new BusinessException("因子「" + definition.label() + "」缺少运算符");
        }
        if (condition.value() == null || !Double.isFinite(condition.value())) {
            throw new BusinessException("因子「" + definition.label() + "」缺少有效阈值");
        }

        if (definition.kind() == FactorKind.BOOLEAN) {
            if (condition.op() != CompareOp.EQ) {
                throw new BusinessException("布尔因子「" + definition.label() + "」只支持「是 / 否」");
            }
            if (condition.value() != 0.0 && condition.value() != 1.0) {
                throw new BusinessException("布尔因子「" + definition.label() + "」取值只能是 是 / 否");
            }
        } else if (Math.abs(condition.value()) > MAX_ABS_VALUE) {
            throw new BusinessException("因子「" + definition.label() + "」阈值超出允许范围");
        }

        validateScoring(condition, definition, allowScoring);
    }

    /** 评分字段校验（WI-011）：布尔条件与排除组不允许参与评分；权重与方向仅随 score=true 出现。 */
    private static void validateScoring(Condition condition, FactorDefinition definition,
                                        boolean allowScoring) {
        String label = definition.label();
        if (!Boolean.TRUE.equals(condition.score())) {
            if (condition.weight() != null || condition.direction() != null) {
                throw new BusinessException("因子「" + label + "」只有参与评分的条件才能设置权重/方向");
            }
            return;
        }
        if (!allowScoring) {
            throw new BusinessException("排除组条件不支持参与评分：" + label);
        }
        if (definition.kind() != FactorKind.NUMBER) {
            throw new BusinessException("因子「" + label + "」不支持参与评分（仅数值因子）");
        }
        if (condition.weight() != null
                && (!Double.isFinite(condition.weight())
                || condition.weight() < MIN_SCORE_WEIGHT
                || condition.weight() > MAX_SCORE_WEIGHT)) {
            throw new BusinessException("因子「" + label + "」的评分权重必须在 "
                    + MIN_SCORE_WEIGHT + " ~ " + MAX_SCORE_WEIGHT + " 之间");
        }
        if (condition.op() == CompareOp.EQ && condition.direction() == null) {
            throw new BusinessException("因子「" + label + "」等值条件参与评分需指定方向");
        }
    }
}
