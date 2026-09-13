package org.haaasaki.financeagent.data.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.BacktestDaily;

/**
 * 回测日度序列 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/BacktestDailyMapper.xml}。
 */
@Mapper
public interface BacktestDailyMapper {

    int batchInsert(@Param("list") List<BacktestDaily> rows);

    List<BacktestDaily> findByTask(@Param("taskId") long taskId);
}
