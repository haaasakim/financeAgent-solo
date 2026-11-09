package org.haaasaki.financeagent.config;

import java.util.List;

import org.haaasaki.financeagent.data.provider.FallbackQuoteProvider;
import org.haaasaki.financeagent.data.provider.QuoteProvider;
import org.haaasaki.financeagent.data.provider.eastmoney.EastmoneyQuoteProvider;
import org.haaasaki.financeagent.data.provider.support.ThrottledHttpClient;
import org.haaasaki.financeagent.data.provider.tencent.TencentQuoteProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 行情 Provider 装配：把腾讯与东财按配置顺序组成回退链。
 *
 * <p>只暴露一个 {@link QuoteProvider} Bean，业务层（同步任务、交易日探测）
 * 不需要感知具体数据源。具体实现类不是 Spring Bean，避免接口注入时出现多个候选。
 */
@Configuration
public class QuoteProviderConfig {

    @Bean
    public QuoteProvider quoteProvider(ThrottledHttpClient httpClient,
                                       ObjectMapper objectMapper,
                                       QuoteProperties quoteProperties,
                                       @Value("${finance.sync.segment-years:3}") int segmentYears,
                                       @Value("${finance.providers.quote.primary:eastmoney}") String primary,
                                       @Value("${finance.providers.quote.eastmoney-host:https://push2his.eastmoney.com}")
                                       String eastmoneyHost) {
        QuoteProvider tencent = new TencentQuoteProvider(
                httpClient, objectMapper, segmentYears, quoteProperties);
        QuoteProvider eastmoney = new EastmoneyQuoteProvider(
                httpClient, objectMapper, eastmoneyHost);
        String preferred = primary == null ? "eastmoney" : primary.trim().toLowerCase();
        List<QuoteProvider> order = "tencent".equals(preferred)
                ? List.of(tencent, eastmoney)
                : List.of(eastmoney, tencent);
        return new FallbackQuoteProvider(order);
    }
}
