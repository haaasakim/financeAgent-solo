package org.haaasaki.financeagent.screener.dsl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 评分方向：数值越大得分越高 / 越小得分越高。
 *
 * <p>仅当条件显式参与评分（{@code score=true}）时生效；未指定时由运算符推断
 * （{@code gt/gte→HIGHER}、{@code lt/lte→LOWER}、{@code eq} 无默认）。
 */
public enum ScoreDirection {

    HIGHER("higher"),
    LOWER("lower");

    private final String key;

    ScoreDirection(String key) {
        this.key = key;
    }

    @JsonValue
    public String key() {
        return key;
    }

    /** 由运算符推断的默认方向；{@code eq} 返回 null（需显式指定）。 */
    public static ScoreDirection defaultFor(CompareOp op) {
        if (op == null) {
            return null;
        }
        return switch (op) {
            case GT, GTE -> HIGHER;
            case LT, LTE -> LOWER;
            case EQ -> null;
        };
    }

    @JsonCreator
    public static ScoreDirection fromKey(String key) {
        if (key != null) {
            for (ScoreDirection direction : values()) {
                if (direction.key.equalsIgnoreCase(key)) {
                    return direction;
                }
            }
            throw new IllegalArgumentException("未知评分方向：" + key);
        }
        return null;
    }
}
