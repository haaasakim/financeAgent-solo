package org.haaasaki.financeagent.market;

import org.haaasaki.financeagent.common.DisplayFormat;

/**
 * 快照数据新鲜度摘要，展示在市场页卡片副标题。
 *
 * @param covered   已有快照的标的数
 * @param fetchedAt 全表最新抓取时刻（ISO 本地时间），空表为 null
 */
public record MarketSnapshotSummary(long covered, String fetchedAt) {

    /** 展示用抓取时刻，秒精度。 */
    public String fetchedAtDisplay() {
        return DisplayFormat.timestamp(fetchedAt);
    }
}
