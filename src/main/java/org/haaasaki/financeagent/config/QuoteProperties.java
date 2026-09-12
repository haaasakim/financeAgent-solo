package org.haaasaki.financeagent.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 行情 Provider 配置。
 *
 * @param tencentKlineHosts 腾讯日K域名，按顺序回退；首个可用者优先。
 *                          默认 web 域优先：实测 {@code ifzq.gtimg.cn} 前置腾讯 WAF，
 *                          对 Java/Python 客户端持续返回 501，而 web 域正常
 */
@ConfigurationProperties(prefix = "finance.providers.quote")
public record QuoteProperties(List<String> tencentKlineHosts) {

    public QuoteProperties {
        tencentKlineHosts = tencentKlineHosts == null || tencentKlineHosts.isEmpty()
                ? List.of("https://web.ifzq.gtimg.cn", "https://ifzq.gtimg.cn")
                : List.copyOf(tencentKlineHosts);
    }
}
