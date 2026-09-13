package org.haaasaki.financeagent.data.provider;

import java.time.LocalDate;
import java.util.List;

/**
 * 历史估值数据源接口。返回值为仍以裸代码标识的原始点，由同步服务映射到 {@code instrument_id}。
 *
 * <p>批量与按日期两种取数方式（东财实测支持多代码 `in` 过滤与日期过滤），
 * 由调用方控制批大小，避免一次请求过大。
 */
public interface ValuationProvider {

    /** 来源标识，写入 {@code source} 列与同步日志。 */
    String name();

    /**
     * 批量代码的全部历史估值（交易日升序）。一次调用对应一批代码；
     * 无数据返回空集合。内部按页拉取并做条数校验，不符抛异常（整批拒绝）。
     */
    List<ValuationPoint> fetchByCodes(List<String> codes);

    /** 指定交易日（含）起的全市场估值（交易日升序）；无数据返回空集合。 */
    List<ValuationPoint> fetchByDate(LocalDate from);
}
