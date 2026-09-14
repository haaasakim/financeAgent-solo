package org.haaasaki.financeagent.screener.factor;

import java.util.List;

/**
 * 因子目录分组（页面左栏）。
 *
 * @param name    分组名
 * @param factors 组内因子，按注册顺序
 */
public record FactorGroup(String name, List<FactorDefinition> factors) {

    /** 组内可用（有数据源）因子数，用于目录标签页计数。 */
    public int availableCount() {
        return (int) factors.stream().filter(FactorDefinition::available).count();
    }
}
