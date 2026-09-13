package org.haaasaki.financeagent.market;

import java.util.List;

import org.haaasaki.financeagent.data.mapper.FactorDailyMapper;
import org.haaasaki.financeagent.data.mapper.InstrumentMapper;
import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;
import org.springframework.stereotype.Service;

/**
 * 因子查询服务：供页面与 JSON 接口验证 {@code factor_daily} 的落库结果。
 *
 * <p>只读本地表，不做计算；计算口径见 docs/work-items/WI-003-指标计算与因子表.md。
 */
@Service
public class FactorQueryService {

    /** 单次查询条数上限，防止误传大数值拉全历史。 */
    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 60;

    private final FactorDailyMapper factorDailyMapper;
    private final InstrumentMapper instrumentMapper;

    public FactorQueryService(FactorDailyMapper factorDailyMapper,
                              InstrumentMapper instrumentMapper) {
        this.factorDailyMapper = factorDailyMapper;
        this.instrumentMapper = instrumentMapper;
    }

    /** 条数归一化：非正取默认值，超上限截断。 */
    public static int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /**
     * @param instrumentId 标的 ID，如 {@code 000001.SZ}
     * @param limit        条数，超出 {@value #MAX_LIMIT} 或非正时归一化
     */
    public FactorView findRecent(String instrumentId, int limit) {
        int effectiveLimit = normalizeLimit(limit);
        List<FactorDaily> rows = factorDailyMapper.findRecent(instrumentId, effectiveLimit);
        Instrument instrument = instrumentMapper.findById(instrumentId).orElse(null);
        return new FactorView(instrument, rows);
    }
}
