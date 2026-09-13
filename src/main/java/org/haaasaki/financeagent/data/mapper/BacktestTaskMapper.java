package org.haaasaki.financeagent.data.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.BacktestTask;

/**
 * 回测任务 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/BacktestTaskMapper.xml}。
 * 每次回测插入新记录，结果按 id 归属，不做覆盖更新。
 */
@Mapper
public interface BacktestTaskMapper {

    int insert(BacktestTask task);

    /** 同一事务内取刚插入的 id（SQLite 连接级）。 */
    Long lastInsertId();

    Optional<BacktestTask> findById(@Param("id") long id);

    List<BacktestTask> findRecent(@Param("limit") int limit);

    int markRunning(@Param("id") long id,
                    @Param("progressTotal") int progressTotal,
                    @Param("startedAt") String startedAt);

    int updateProgressTotal(@Param("id") long id, @Param("progressTotal") int progressTotal);

    int updateProgress(@Param("id") long id, @Param("progressDone") int progressDone);

    int markSuccess(@Param("id") long id,
                    @Param("progressDone") int progressDone,
                    @Param("finalNav") double finalNav,
                    @Param("cumulativeReturn") Double cumulativeReturn,
                    @Param("annualReturn") Double annualReturn,
                    @Param("maxDrawdown") Double maxDrawdown,
                    @Param("sharpe") Double sharpe,
                    @Param("winRate") Double winRate,
                    @Param("turnover") Double turnover,
                    @Param("finishedAt") String finishedAt);

    int markFailed(@Param("id") long id,
                   @Param("message") String message,
                   @Param("finishedAt") String finishedAt);
}
