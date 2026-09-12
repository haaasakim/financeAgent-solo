package org.haaasaki.financeagent.data.sync;

/**
 * 同步任务执行结果。
 *
 * @param taskName 任务名，与 {@code sync_log.task_name} 一致
 * @param status   {@code SUCCESS} / {@code PARTIAL} / {@code FAILED}
 * @param total    计划处理的标的数
 * @param success  成功数
 * @param fail     失败数
 * @param message  摘要信息（含失败原因，超长时会被截断）
 */
public record SyncResult(
        String taskName,
        String status,
        int total,
        int success,
        int fail,
        String message) {

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }
}
