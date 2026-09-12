package org.haaasaki.financeagent.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 市场模块配置。
 *
 * @param indexes 市场概览条展示的指数清单，按配置顺序渲染
 */
@ConfigurationProperties(prefix = "finance.market")
public record MarketProperties(List<IndexDef> indexes) {

    /**
     * 指数定义。
     *
     * @param id   全局唯一标识，如 {@code 000001.SH}
     * @param name 展示名称，如 {@code 上证指数}
     */
    public record IndexDef(String id, String name) {
    }
}
