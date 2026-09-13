package org.haaasaki.financeagent.data.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.Instrument;

/**
 * 标的字典 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/InstrumentMapper.xml}。
 * 写入一律使用 upsert（SQLite 的 {@code ON CONFLICT ... DO UPDATE}），保证同步任务可重复执行。
 */
@Mapper
public interface InstrumentMapper {

    /** 批量 upsert，返回受影响行数。 */
    int upsertBatch(@Param("list") List<Instrument> instruments);

    Optional<Instrument> findById(@Param("instrumentId") String instrumentId);

    /** 全部在册标的（不含退市），按 instrument_id 升序。 */
    List<Instrument> findAllListed();

    /** 当前没有任何日K的标的（不含退市），按 instrument_id 升序。用于失败后的断点续传。 */
    List<Instrument> findWithoutDailyBars();

    /** 已有日K的标的（不含退市），按 instrument_id 升序。因子计算只针对这些标的。 */
    List<Instrument> findWithDailyBars();

    List<Instrument> findByType(@Param("instrumentType") String instrumentType);

    long count();

    /** 回填上市日期（仅当原值为 NULL 时生效）。 */
    int updateListedAt(@Param("instrumentId") String instrumentId, @Param("listedAt") LocalDate listedAt);
}
