package org.haaasaki.financeagent.data.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.SyncLogEntry;

/**
 * 同步审计 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/SyncLogMapper.xml}。
 *
 * <p>设计说明：M1 的同步是命令行阻塞式执行，进程退出前看不到 RUNNING 状态，
 * 因此这里**只记录终态**（结束后写一条），不做 start/finish 两段式。
 * 「任务进行中」的职责在 M2 由 {@code task} 表承担。
 */
@Mapper
public interface SyncLogMapper {

    int insert(@Param("taskName") String taskName,
               @Param("provider") String provider,
               @Param("status") String status,
               @Param("totalCount") int totalCount,
               @Param("successCount") int successCount,
               @Param("failCount") int failCount,
               @Param("message") String message,
               @Param("startedAt") String startedAt);

    /** 最近若干条记录（倒序）。 */
    List<SyncLogEntry> findRecent(@Param("limit") int limit);

    /** 最后一条记录，无记录时返回 null。 */
    SyncLogEntry findLast();
}
