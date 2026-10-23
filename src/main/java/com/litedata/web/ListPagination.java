package com.litedata.web;

import com.litedata.service.QueryPage;
import java.util.Map;
import org.springframework.ui.Model;

/** Shared list navigation; counting precedes fetching so removed rows cannot strand a page. */
public final class ListPagination {
    private ListPagination() {}

    public static QueryPage prepare(Model model,String path,Map<String,String> params,long total) {
        var requested=QueryPage.parse(params,20);
        long pages=Math.max(1,(total+requested.pageSize()-1)/requested.pageSize());
        var page=new QueryPage((int)Math.min(requested.pageNum(),pages),requested.pageSize());
        model.addAttribute("pagePath",path);
        model.addAttribute("pageNum",page.pageNum()); model.addAttribute("pageSize",page.pageSize());
        model.addAttribute("totalCount",total); model.addAttribute("totalPages",pages);
        model.addAttribute("hasPrevious",page.pageNum()>1); model.addAttribute("hasMore",page.pageNum()<pages);
        model.addAttribute("pageSizes",java.util.List.of(10,20,50,100));
        return page;
    }
}
