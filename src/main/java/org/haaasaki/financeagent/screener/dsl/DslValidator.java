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
        validateGroup("全部满足", dsl.all(), registry);
        validateGroup("任一满足", dsl.any(), registry);
        validateGroup("排除", dsl.not(), registry);
    }

    private static void validateGroup(String groupLabel, java.util.List<Condition> conditions,
                                      FactorRegistry registry) {
        if (conditions.size() > MAX_PER_GROUP) {
            throw new BusinessException(groupLabel + "的条件不能超过 " + MAX_PER_GROUP + " 个");
        }
        for (Condition condition : conditions) {
            validateCondition(groupLabel, condition, registry);
        }
    }

    private static void validateCondition(String groupLabel, Condition condition,
                                          FactorRegistry registry) {
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
    }
}
