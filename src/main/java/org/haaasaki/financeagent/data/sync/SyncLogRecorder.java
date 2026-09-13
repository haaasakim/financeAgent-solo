package org.haaasaki.financeagent.data.sync;

import java.time.LocalDateTime;

import org.haaasaki.financeagent.data.mapper.SyncLogMapper;
import org.springframework.stereotype.Component;

/**
 * 同步审计记录器：统一 `sync_log` 的写入口径（只记终态）。
 *
 * <p>从 {@link MarketDataSyncService} 抽出，供同步任务与因子计算任务共用。
 */
@Component
public class SyncLogRecorder {

    private final SyncLogMapper syncLogMapper;

    public SyncLogRecorder(SyncLogMapper syncLogMapper) {
        this.syncLogMapper = syncLogMapper;
    }

    /**
     * @param taskName   任务名，如 {@code SYNC_MARKET_SNAPSHOT} / {@code FACTOR_INIT}
     * @param provider   执行者标识，如 {@code TENCENT_FQKLINE} / {@code LOCAL}
     * @param status     {@code SUCCESS} / {@code PARTIAL} / {@code FAILED}
     * @param total      处理总数
     * @param success    成功数
     * @param fail       失败数
     * @param message    结果摘要，可为 null
     * @param startedAt  任务开始时刻
     */
    public void record(String taskName, String provider, String status,
                       int total, int success, int fail, String message, LocalDateTime startedAt) {
        syncLogMapper.insert(taskName, provider, status, total, success, fail, message, startedAt.toString());
    }
}
