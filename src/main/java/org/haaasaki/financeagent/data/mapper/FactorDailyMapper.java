package org.haaasaki.financeagent.data.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.FactorDaily;

/**
 * 日频因子 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/FactorDailyMapper.xml}。
 * 因子由本地日K派生，写入走 upsert 保证任务幂等。
 */
@Mapper
public interface FactorDailyMapper {

    int upsertBatch(@Param("list") List<FactorDaily> factors);

    long count();

    long countByInstrument(@Param("instrumentId") String instrumentId);

    long countDistinctInstruments();

    /** 已有任意因子行的标的集合，供增量任务判断「重算窗口」还是「全量回填」。 */
    List<String> findInstrumentIdsWithFactors();

    /**
     * 最近 {@code limit} 个交易日的因子，按交易日倒序（最新在前），供页面验证使用。
     *
     * @param limit 条数上限，调用方负责限幅
     */
    List<FactorDaily> findRecent(@Param("instrumentId") String instrumentId,
                                 @Param("limit") int limit);
}
