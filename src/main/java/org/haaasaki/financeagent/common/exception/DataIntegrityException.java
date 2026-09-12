package org.haaasaki.financeagent.common.exception;

/**
 * 数据完整性校验失败，<b>不可重试</b>。
 *
 * <p>典型场景：腾讯接口在 count 超限时会静默降级返回部分数据（HTTP 仍为 200），
 * 落库前校验返回条数与预期不符即抛出本异常。
 *
 * <p>处理原则：<b>整批拒绝</b>，不写入任何部分数据。部分写入产生的数据缺口
 * 比同步失败更危险，因为它不会被发现（见 docs/DATA_SOURCES.md 第 11 节）。
 */
public class DataIntegrityException extends RuntimeException {

    public DataIntegrityException(String message) {
        super(message);
    }

    public DataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
