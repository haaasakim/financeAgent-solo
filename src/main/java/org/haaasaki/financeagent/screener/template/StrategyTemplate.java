package org.haaasaki.financeagent.screener.template;

import org.haaasaki.financeagent.screener.dsl.ScreenerDsl;

/**
 * 预设策略模板。
 *
 * @param key         稳定标识，页面参数 {@code template=key}
 * @param name        展示名
 * @param description 一句话说明（条件摘要由页面渲染）
 * @param dsl         条件树
 */
public record StrategyTemplate(
        String key,
        String name,
        String description,
        ScreenerDsl dsl) {
}
