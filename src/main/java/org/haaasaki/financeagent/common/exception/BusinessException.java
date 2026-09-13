package org.haaasaki.financeagent.common.exception;

/**
 * 业务异常：用户输入或操作不满足业务规则，<b>用户可见</b>。
 *
 * <p>与之相对，{@link DataSyncException} 表示可重试的同步失败，
 * {@link DataIntegrityException} 表示数据不符合契约、需人工介入。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
