package org.haaasaki.financeagent.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 轻量请求日志：记录非静态资源请求的方法、路径、查询参数、状态码与耗时。
 *
 * <p>用于页面与片段接口的排查：参数、状态、耗时一行可见；4xx 记 WARN、5xx 记 ERROR、
 * 超过阈值的慢请求记 WARN。只记录查询字符串，不记录请求体与响应体（见 docs/BACKEND_GUIDE.md 第 8 节）。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** 超过该耗时的成功请求也按 WARN 记录，便于发现慢查询。 */
    private static final long SLOW_MILLIS = 1000;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/") || path.startsWith("/js/") || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            String target = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            int status = response.getStatus();

            if (status >= 500) {
                log.error("{} {} -> {} ({} ms)", request.getMethod(), target, status, millis);
            } else if (status >= 400 || millis >= SLOW_MILLIS) {
                log.warn("{} {} -> {} ({} ms)", request.getMethod(), target, status, millis);
            } else {
                log.info("{} {} -> {} ({} ms)", request.getMethod(), target, status, millis);
            }
        }
    }
}
