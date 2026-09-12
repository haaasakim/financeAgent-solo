package org.haaasaki.financeagent.data.provider.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.haaasaki.financeagent.common.exception.DataSyncException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * 熔断冷却测试：本地 HttpServer 模拟连续 501（腾讯实测限流表现），验证阈值触发冷却后自动恢复。
 */
class ThrottledHttpClientTest {

    private HttpServer server;
    private String url;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kline", exchange -> {
            int status = requests.incrementAndGet() <= 2 ? 501 : 200;
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/kline";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("连续失败达到阈值后冷却，冷却结束自动恢复")
    void coolsDownAfterConsecutiveFailures() {
        // maxRetry=0：一次 get 只发一次请求；阈值 2，冷却 1 秒
        ThrottledHttpClient client = new ThrottledHttpClient(2, 2, 0, 1000, 2, 1);

        assertThatThrownBy(() -> client.get(url, StandardCharsets.UTF_8))
                .isInstanceOf(DataSyncException.class);
        assertThatThrownBy(() -> client.get(url, StandardCharsets.UTF_8))
                .isInstanceOf(DataSyncException.class);

        long start = System.currentTimeMillis();
        String body = client.get(url, StandardCharsets.UTF_8);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(body).isEqualTo("ok");
        assertThat(elapsed).isGreaterThanOrEqualTo(900);
    }
}
