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

    /** 分页查询；排序列与方向必须来自服务层白名单映射（camelCase key → snake_case 列）。 */
    List<BacktestTask> findPage(@Param("sortColumn") String sortColumn,
                                @Param("direction") String direction,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    long countAll();

    /** 该策略未结束的任务数（PENDING / RUNNING），删除策略前检查。 */
    long countActiveByStrategy(@Param("strategyId") long strategyId);

    /** 删除该策略的全部任务记录，返回删除条数。 */
    int deleteByStrategy(@Param("strategyId") long strategyId);

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
