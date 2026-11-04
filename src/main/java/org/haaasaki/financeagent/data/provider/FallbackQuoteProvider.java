package org.haaasaki.financeagent.data.provider;

import java.time.LocalDate;
import java.util.List;

import org.haaasaki.financeagent.common.exception.DataIntegrityException;
import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.haaasaki.financeagent.data.model.DailyBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行情源回退链：按配置顺序依次尝试，前一个源失败时自动切换。
 *
 * <p>切换条件为 {@link DataSyncException}（网络 / 限流 / 5xx）或
 * {@link DataIntegrityException}（响应结构或条数异常）；参数错误不切换。
 * 某个源正常返回空列表表示「该标的在该源没有数据」，直接返回空，不再切换：
 * 腾讯在批量抓取时会被 WAF 拦截，若对每个无数据标的都回退腾讯，会拖垮整个回填任务。
 */
public class FallbackQuoteProvider implements QuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackQuoteProvider.class);

    private final List<QuoteProvider> delegates;

    public FallbackQuoteProvider(List<QuoteProvider> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("行情源回退链不能为空");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public String name() {
        return delegates.stream().map(QuoteProvider::name).reduce((a, b) -> a + "+" + b).orElse("NONE");
    }

    @Override
    public List<DailyBar> fetchDailyBars(String instrumentId, LocalDate start, LocalDate end) {
        RuntimeException lastError = null;
        for (int i = 0; i < delegates.size(); i++) {
            QuoteProvider delegate = delegates.get(i);
            try {
                // 空列表是合法的「该源无数据」，直接返回，不为每个无数据标的额外打一次备源
                return delegate.fetchDailyBars(instrumentId, start, end);
            } catch (DataSyncException | DataIntegrityException e) {
                lastError = e;
                if (i < delegates.size() - 1) {
                    log.warn("行情源 {} 失败，切换下一源（{} {}~{}）：{}",
                            delegate.name(), instrumentId, start, end, e.getMessage());
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return List.of();
    }
}
