package org.haaasaki.financeagent.data.model;

/**
 * 同步审计记录，供首页展示最近若干次同步结果。
 *
 * <p>时间字段以字符串承载，读取时已是可展示格式，避免在模板里再做转换。
 *
 * @param id           日志主键
 * @param taskName     任务名：{@code SYNC_INSTRUMENTS} / {@code INIT_DAILY_BARS} / {@code INCR_DAILY_BARS}
 * @param provider     数据来源标识，如 {@code TENCENT_FQKLINE}
 * @param status       终态：{@code SUCCESS} / {@code PARTIAL} / {@code FAILED}
 * @param totalCount   计划处理的标的数
 * @param successCount 成功数
 * @param failCount    失败数（含「区间内无数据」的标的）
 * @param message      结果摘要，含失败原因（截断到 1000 字符），可为 null
 * @param startedAt    任务开始时间，ISO 文本
 * @param finishedAt   任务结束时间，ISO 文本
 */
public record SyncLogEntry(
        long id,
        String taskName,
        String provider,
        String status,
        int totalCount,
        int successCount,
        int failCount,
        String message,
        String startedAt,
        String finishedAt) {

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }
}
