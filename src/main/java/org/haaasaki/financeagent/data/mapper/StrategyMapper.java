package org.haaasaki.financeagent.data.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.Strategy;

/**
 * 选股策略 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/StrategyMapper.xml}。
 * 保存按 name 唯一键 upsert，重名视为覆盖更新。
 */
@Mapper
public interface StrategyMapper {

    int upsert(@Param("name") String name,
               @Param("description") String description,
               @Param("dsl") String dsl);

    List<Strategy> findAll();

    Optional<Strategy> findById(@Param("id") long id);

    Optional<Strategy> findByName(@Param("name") String name);

    int deleteById(@Param("id") long id);
}
