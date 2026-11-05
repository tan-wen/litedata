package com.litedata.web;

import com.litedata.service.QueryPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ui.Model;

/**
 * Shared list navigation; counting precedes fetching so removed rows cannot strand a page.
 */
public final class ListPagination {
    private ListPagination() {
    }

    public static QueryPage prepare(Model model, String path, Map<String, String> params, long total) {
        var requested = QueryPage.parse(params, 20);
        long pages = Math.max(1, total / requested.pageSize() + (total % requested.pageSize() == 0 ? 0 : 1));
        var page = new QueryPage((int) Math.min(requested.pageNum(), pages), requested.pageSize());
        model.addAttribute("pagePath", path);
        model.addAttribute("pageNum", page.pageNum());
        model.addAttribute("pageSize", page.pageSize());
        model.addAttribute("totalCount", total);
        model.addAttribute("totalPages", pages);
        model.addAttribute("hasPrevious", page.pageNum() > 1);
        model.addAttribute("hasMore", page.pageNum() < pages);
        model.addAttribute("pageSizes", List.of(10, 20, 50, 100));
        model.addAttribute("pageNumbers", pageNumbers(page.pageNum(), pages));
        return page;
    }

    /**
     * At most seven slots: first/last pages and a sliding window; zero denotes a gap.
     */
    private static List<Long> pageNumbers(int current, long total) {
        var numbers = new ArrayList<Long>(7);
        if (total <= 7) {
            for (long number = 1; number <= total; number++) numbers.add(number);
        } else {
            long start = current <= 4 ? 2 : current >= total - 3 ? total - 4 : current - 1L;
            long end = current <= 4 ? 5 : current >= total - 3 ? total - 1 : current + 1L;
            numbers.add(1L);
            if (start > 2) numbers.add(0L);
            for (long number = start; number <= end; number++) numbers.add(number);
            if (end < total - 1) numbers.add(0L);
            numbers.add(total);
        }
        return numbers;
    }
}
