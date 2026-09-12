package org.haaasaki.financeagent.web;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * 页面层异常处理：数据库访问失败等运行时错误。
 *
 * <p>HTMX 请求返回错误片段（含重试按钮），普通请求返回错误页；
 * 不向页面暴露堆栈，只给可读摘要。
 */
@ControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ModelAndView handleDataAccess(DataAccessException e, HttpServletRequest request) {
        log.error("数据访问失败 {} {}", request.getMethod(), request.getRequestURI(), e);

        String retryUrl = request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        boolean htmx = "true".equals(request.getHeader("HX-Request"));

        ModelAndView mv = new ModelAndView(htmx ? "fragments/market-table-error" : "error");
        mv.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        mv.addObject("message", "数据访问失败，请稍后重试");
        mv.addObject("retryUrl", retryUrl);
        return mv;
    }
}
