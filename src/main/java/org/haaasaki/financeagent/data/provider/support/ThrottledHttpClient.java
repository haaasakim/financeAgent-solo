package org.haaasaki.financeagent.data.provider.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;

import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 带限速与重试的 HTTP 客户端，供各外部数据源 Provider 复用。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>限速</b>：按配置的 QPS 串行节流。实测腾讯接口 3.3 QPS 连发 30 次零失败，
 *       默认取 5 QPS 留出余量；限流后约 20 秒可恢复。</li>
 *   <li><b>重试</b>：最多 maxRetry 次，指数退避（1s / 2s / 4s）。</li>
 *   <li><b>熔断冷却</b>：连续失败达到阈值（默认 5 次）暂停冷却（默认 60s）。
 *       实测腾讯在持续批量抓取时会返回 HTTP 501，冷却后恢复；无冷却时重试风暴会加剧限流。</li>
 *   <li><b>不重试 4xx</b>：除 429（限流）与新浪 456（反爬限流，实测连续全量抓取时出现）外，
 *       4xx 说明请求本身有问题，重试无意义。</li>
 *   <li><b>编码由调用方指定</b>：腾讯快照接口返回 GBK，JSON 接口为 UTF-8，
 *       解码错误会导致中文乱码，因此不做自动探测。</li>
 * </ul>
 *
 * <p>限速与熔断状态是 {@code synchronized} 的，即全局串行。同步任务目前单线程执行，够用；
 * 若将来改为多线程拉取，需要改为令牌桶。
 */
@Component
public class ThrottledHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ThrottledHttpClient.class);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final int maxRetry;
    private final long minIntervalMillis;
    private final int circuitThreshold;
    private final long cooldownMillis;

    private volatile long lastRequestAt;
    private int consecutiveFailures;

    public ThrottledHttpClient(
            @Value("${finance.providers.http.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${finance.providers.http.read-timeout-seconds:15}") int readTimeoutSeconds,
            @Value("${finance.providers.http.max-retry:3}") int maxRetry,
            @Value("${finance.providers.http.rate-limit-qps:5}") int rateLimitQps,
            @Value("${finance.providers.http.circuit-breaker-threshold:5}") int circuitThreshold,
            @Value("${finance.providers.http.cooldown-seconds:60}") int cooldownSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        this.maxRetry = Math.max(0, maxRetry);
        this.minIntervalMillis = Math.max(1, 1000L / Math.max(1, rateLimitQps));
        this.circuitThreshold = Math.max(1, circuitThreshold);
        this.cooldownMillis = Math.max(1000L, cooldownSeconds * 1000L);
    }

    /**
     * 发起 GET 请求，返回解码后的响应体。
     *
     * @param url     完整 URL
     * @param charset 响应编码，腾讯快照传 GBK，其余通常 UTF-8
     * @param referer 部分接口（如巨潮）必须携带，可为 null
     * @throws DataSyncException 重试耗尽或遇到不可重试的状态码
     */
    public String get(String url, Charset charset, String referer) {
        URI uri = URI.create(url);
        DataSyncException lastError = null;

        for (int attempt = 1; attempt <= maxRetry + 1; attempt++) {
            awaitCooldownIfCircuitOpen();
            throttle();
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .GET()
                        .timeout(readTimeout)
                        .header("User-Agent", USER_AGENT);
                if (referer != null && !referer.isBlank()) {
                    builder.header("Referer", referer);
                }

                HttpResponse<byte[]> response =
                        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    recordSuccess();
                    return new String(response.body(), charset);
                }

                recordFailure();
                lastError = new DataSyncException("HTTP " + status + " - " + url);
                if (status >= 400 && status < 500 && status != 429 && status != 456) {
                    log.warn("请求返回 {}，判定为不可重试：{}", status, url);
                    break;
                }
            } catch (IOException e) {
                recordFailure();
                lastError = new DataSyncException("请求失败 - " + url, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataSyncException("请求被中断 - " + url, e);
            }

            if (attempt <= maxRetry) {
                long backoff = 1000L << (attempt - 1);
                log.debug("第 {} 次请求失败，{} ms 后重试：{}", attempt, backoff, url);
                sleepQuietly(backoff);
            }
        }

        throw lastError != null ? lastError : new DataSyncException("请求失败 - " + url);
    }

    public String get(String url, Charset charset) {
        return get(url, charset, null);
    }

    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long wait = minIntervalMillis - (now - lastRequestAt);
        if (wait > 0) {
            sleepQuietly(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    /**
     * 连续失败达到阈值时全局冷却，随后清零计数。
     *
     * <p>在锁内休眠是有意为之：冷却期间所有请求都应停下，
     * 继续重试只会加剧外部限流（实测腾讯批量抓取触发 501 的情况）。
     */
    private synchronized void awaitCooldownIfCircuitOpen() {
        if (consecutiveFailures < circuitThreshold) {
            return;
        }
        log.warn("连续失败 {} 次，触发熔断冷却 {} 秒（疑似外部限流），冷却结束后恢复",
                consecutiveFailures, cooldownMillis / 1000);
        consecutiveFailures = 0;
        sleepQuietly(cooldownMillis);
    }

    private synchronized void recordFailure() {
        consecutiveFailures++;
    }

    private synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSyncException("等待被中断", e);
        }
    }
}
