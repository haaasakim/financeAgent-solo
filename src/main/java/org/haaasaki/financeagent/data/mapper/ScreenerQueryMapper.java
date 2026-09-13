package org.haaasaki.financeagent.data.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.haaasaki.financeagent.data.model.FactorValue;
import org.haaasaki.financeagent.data.model.ScreenerRow;
import org.haaasaki.financeagent.data.model.SqlCondition;

/**
 * 选股求值 Mapper。
 *
 * <p>SQL 见 {@code resources/mapper/ScreenerQueryMapper.xml}。
 * 条件由 {@code ScreenerService} 从注册表解析为 {@link SqlCondition} 后传入：
 * 表达式与运算符来自白名单，阈值走参数绑定（见 docs/BACKEND_GUIDE.md 第 4 节）。
 * 明细不做 SQL 分页，由服务层评分排序后分页（WI-005）。
 */
@Mapper
public interface ScreenerQueryMapper {

    long countCandidates(@Param("all") List<SqlCondition> all,
                         @Param("any") List<SqlCondition> any,
                         @Param("excludes") List<SqlCondition> excludes);

    List<ScreenerRow> findAllCandidates(@Param("all") List<SqlCondition> all,
                                        @Param("any") List<SqlCondition> any,
                                        @Param("excludes") List<SqlCondition> excludes);

    /** 按白名单表达式取全市场因子值，供服务层在命中集内计算评分。 */
    List<FactorValue> findFactorValues(@Param("expression") String expression);
}
