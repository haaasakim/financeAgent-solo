package org.haaasaki.financeagent.data.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.BacktestTrade;

/**
 * 回测订单流水 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/BacktestTradeMapper.xml}。
 */
@Mapper
public interface BacktestTradeMapper {

    int batchInsert(@Param("list") List<BacktestTrade> rows);

    List<BacktestTrade> findPage(@Param("taskId") long taskId,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    long countByTask(@Param("taskId") long taskId);

    /** 任务的全部成交订单，按 orderNo 升序；供「按标的配对」使用。 */
    List<BacktestTrade> findFilledByTask(@Param("taskId") long taskId);
}
