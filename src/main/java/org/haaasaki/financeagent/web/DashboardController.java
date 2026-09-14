package org.haaasaki.financeagent.web;

import org.haaasaki.financeagent.data.mapper.SyncLogMapper;
import org.haaasaki.financeagent.data.support.DataStatisticsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首页：本地数据总览。
 *
 * <p>页面让「数据同步到什么程度」一眼可见，并提供市场 → 选股 → 回测的主路径入口。
 *
 * <p>本页只读，不提供同步按钮——同步目前是命令行触发，
 * 数据同步仍由命令行触发。
 */
@Controller
public class DashboardController {

    private static final int RECENT_SYNC_LIMIT = 10;

    private final DataStatisticsService statisticsService;
    private final SyncLogMapper syncLogMapper;

    public DashboardController(DataStatisticsService statisticsService,
                               SyncLogMapper syncLogMapper) {
        this.statisticsService = statisticsService;
        this.syncLogMapper = syncLogMapper;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("stats", statisticsService.collect());
        model.addAttribute("recentSyncs", syncLogMapper.findRecent(RECENT_SYNC_LIMIT));
        return "dashboard";
    }
}
