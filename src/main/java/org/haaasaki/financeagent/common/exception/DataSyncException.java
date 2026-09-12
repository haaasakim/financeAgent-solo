package org.haaasaki.financeagent.common.exception;

/**
 * 数据同步失败，<b>可通过重试解决</b>（网络抖动、限流、5xx）。
 *
 * <p>与之相对的 {@link DataIntegrityException} 表示数据本身不符合契约，
 * 重试没有意义，必须人工介入。
 */
public class DataSyncException extends RuntimeException {

    public DataSyncException(String message) {
        super(message);
    }

    public DataSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
