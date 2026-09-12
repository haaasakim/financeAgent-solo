package org.haaasaki.financeagent.web;

import java.util.Optional;

import org.haaasaki.financeagent.market.KlineRange;
import org.haaasaki.financeagent.market.MarketListQuery;
import org.haaasaki.financeagent.market.MarketQueryService;
import org.haaasaki.financeagent.market.MarketSortField;
import org.haaasaki.financeagent.market.StockDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * 市场模块控制器：市场列表页、个股详情页、HTMX 表格片段。
 *
 * <p>只做参数绑定、调用 Service、选择视图；所有数据来自本地库。
 * 列表筛选与排序参数由 Service 归一化，未知排序键回退默认值而不是报错。
 */
@Controller
public class MarketController {

    private final MarketQueryService marketQueryService;

    public MarketController(MarketQueryService marketQueryService) {
        this.marketQueryService = marketQueryService;
    }

    @GetMapping("/market")
    public String market(@RequestParam(required = false) String keyword,
                         @RequestParam(required = false) String board,
                         @RequestParam(required = false) String type,
                         @RequestParam(required = false) String sort,
                         @RequestParam(required = false) String dir,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "20") int size,
                         Model model) {
        model.addAttribute("result", marketQueryService.list(
                MarketListQuery.of(keyword, board, type, sort, dir, page, size)));
        model.addAttribute("indexQuotes", marketQueryService.indexQuotes());
        model.addAttribute("summary", marketQueryService.snapshotSummary());
        model.addAttribute("sortFields", MarketSortField.values());
        return "market/list";
    }

    /** HTMX 局部刷新：只返回表格片段。 */
    @GetMapping("/fragments/market/table")
    public String tableFragment(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String board,
                                @RequestParam(required = false) String type,
                                @RequestParam(required = false) String sort,
                                @RequestParam(required = false) String dir,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size,
                                Model model) {
        model.addAttribute("result", marketQueryService.list(
                MarketListQuery.of(keyword, board, type, sort, dir, page, size)));
        model.addAttribute("sortFields", MarketSortField.values());
        return "fragments/market-table";
    }

    @GetMapping("/market/stock/{instrumentId}")
    public String stock(@PathVariable String instrumentId,
                        @RequestParam(defaultValue = "6m") String range,
                        Model model) {
        Optional<StockDetail> detail =
                marketQueryService.findStockDetail(instrumentId, KlineRange.from(range));
        if (detail.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "标的不存在：" + instrumentId);
        }
        model.addAttribute("detail", detail.get());
        model.addAttribute("ranges", KlineRange.values());
        return "market/detail";
    }
}
