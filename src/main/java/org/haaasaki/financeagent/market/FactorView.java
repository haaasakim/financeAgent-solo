package org.haaasaki.financeagent.market;

import java.util.List;

import org.haaasaki.financeagent.data.model.FactorDaily;
import org.haaasaki.financeagent.data.model.Instrument;

/**
 * 因子查询视图模型（验证页使用）。
 *
 * @param instrument 标的档案；字典中不存在时为 null（仍展示库中已有因子行）
 * @param rows       因子行，按交易日倒序（最新在前）
 */
public record FactorView(Instrument instrument, List<FactorDaily> rows) {
}
