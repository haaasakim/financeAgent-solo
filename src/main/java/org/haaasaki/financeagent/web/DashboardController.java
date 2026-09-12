package org.haaasaki.financeagent.web;

import org.haaasaki.financeagent.data.mapper.SyncLogMapper;
import org.haaasaki.financeagent.data.support.DataStatisticsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首页：本地数据总览。
 *
 * <p>M1 只有数据层，页面的职责是让「数据到底同步到什么程度」一眼可见，
 * 而不是展示行情。市场 / 选股 / 回测三个模块从 M2 起逐个落地。
 *
 * <p>本页只读，不提供同步按钮——同步目前是命令行触发，
 * 异步任务框架（可在页面上触发并观察进度）在 M2 提供。
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
