package org.haaasaki.financeagent.screener.factor;

import java.util.List;

/**
 * 因子目录分组（页面左栏）。
 *
 * @param name    分组名
 * @param factors 组内因子，按注册顺序
 */
public record FactorGroup(String name, List<FactorDefinition> factors) {
}
