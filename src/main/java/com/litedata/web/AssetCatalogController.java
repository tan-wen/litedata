package com.litedata.web;

import com.litedata.catalog.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/assets")
public class AssetCatalogController {
    private final AssetCatalogService catalog;
    public AssetCatalogController(AssetCatalogService catalog) { this.catalog=catalog; }
    @ModelAttribute("activeNav") String activeNav() { return "assets"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest request) { return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean canManageUsers(HttpServletRequest request) { return request.isUserInRole("ADMIN"); }
    @InitBinder("draft") void bind(WebDataBinder binder) {
        binder.setAllowedFields("businessName","subjectArea","owner","description","tags","updateFrequency","sensitivity","version");
    }
    @GetMapping String list(@RequestParam Map<String,String> params,Model model) {
        var filters=AssetFilters.from(params);
        var page=ListPagination.prepare(model,"/assets",params,catalog.count(filters));
        model.addAttribute("assets",catalog.list(filters,page)); model.addAttribute("filters",filters);
        model.addAttribute("keyword",filters.keyword()); model.addAttribute("assetType",filters.assetType());
        model.addAttribute("subjectArea",filters.subjectArea()); model.addAttribute("owner",filters.owner());
        model.addAttribute("tag",filters.tag()); model.addAttribute("assetStatus",filters.status());
        model.addAttribute("assetTypes",AssetType.values()); model.addAttribute("assetStatuses",AssetCatalogService.STATUSES);
        model.addAttribute("stats",catalog.stats()); return "assets";
    }
    @GetMapping("/{type}/{id}") String detail(@PathVariable String type,@PathVariable long id,Model model) {
        var asset=catalog.require(type,id); var profile=catalog.profile(asset);
        model.addAttribute("asset",asset); model.addAttribute("columns",catalog.columns(asset));
        model.addAttribute("qualityTasks",catalog.qualityLinks(asset)); model.addAttribute("qualityTaskCount",catalog.qualityCount(asset));
        model.addAttribute("profileUpdatedAt",profile==null?null:profile.getUpdatedAt());
        model.addAttribute("profileUpdatedBy",profile==null?null:profile.getUpdatedBy()); return "asset-detail";
    }
    @GetMapping("/{type}/{id}/edit") String edit(@PathVariable String type,@PathVariable long id,Model model) {
        var asset=catalog.require(type,id); var profile=catalog.profile(asset);
        return form(model,asset,profile==null?new AssetProfile():profile);
    }
    @PostMapping("/{type}/{id}") String update(@PathVariable String type,@PathVariable long id,@ModelAttribute("draft") AssetProfile draft,
            BindingResult errors,Model model,Principal principal,RedirectAttributes redirect) {
        var asset=catalog.require(type,id);
        try {
            if(errors.hasErrors()) throw new IllegalArgumentException("表单格式无效，请检查版本和字段内容");
            catalog.save(type,id,draft,principal.getName());
            redirect.addFlashAttribute("success","资产描述已保存"); return "redirect:/assets";
        } catch(IllegalArgumentException e) { model.addAttribute("error",e.getMessage()); return form(model,asset,draft); }
    }
    private String form(Model model,AssetEntry asset,AssetProfile draft) {
        model.addAttribute("asset",asset); model.addAttribute("draft",draft);
        model.addAttribute("sensitivityOptions",AssetCatalogService.SENSITIVITIES); return "asset-form";
    }
}
