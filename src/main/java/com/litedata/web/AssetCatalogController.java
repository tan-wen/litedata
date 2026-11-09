package com.litedata.web;

import com.litedata.catalog.*;
import com.litedata.metric.MetricType;
import com.litedata.service.QueryPage;
import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;
import java.util.*;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@RequestMapping("/assets")
public class AssetCatalogController {
    private static final List<String> FILTER_KEYS = List.of("keyword", "assetType", "assetCategory", "subjectArea", "owner", "tag", "assetStatus");

    public record Category(String type, String label, long count, String path, List<Subcategory> children) {
    }

    public record Subcategory(String key, String label, long count, String path) {
    }

    private final AssetCatalogService catalog;

    public AssetCatalogController(AssetCatalogService catalog) {
        this.catalog = catalog;
    }

    @ModelAttribute("activeNav")
    String activeNav() {
        return "assets";
    }

    @ModelAttribute("canEdit")
    boolean canEdit(HttpServletRequest request) {
        return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER");
    }

    @ModelAttribute("canManageUsers")
    boolean canManageUsers(HttpServletRequest request) {
        return request.isUserInRole("ADMIN");
    }

    @InitBinder("draft")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("businessName", "subjectArea", "owner", "description", "tags", "updateFrequency", "sensitivity", "version");
    }

    @GetMapping
    String list(@RequestParam Map<String, String> params, Model model) {
        var filters = AssetFilters.from(params);
        var page = ListPagination.prepare(model, "/assets", params, catalog.count(filters));
        var assets = catalog.list(filters, page);
        model.addAttribute("assets", assets);
        model.addAttribute("filters", filters);
        model.addAttribute("keyword", filters.keyword());
        model.addAttribute("assetType", filters.assetType());
        model.addAttribute("assetCategory", filters.assetCategory());
        model.addAttribute("subjectArea", filters.subjectArea());
        model.addAttribute("owner", filters.owner());
        model.addAttribute("tag", filters.tag());
        model.addAttribute("assetStatus", filters.status());
        model.addAttribute("assetStatuses", AssetCatalogService.STATUSES);
        var stats = catalog.stats();
        model.addAttribute("stats", stats);
        var categories = new ArrayList<Category>();
        var categoryCounts = catalog.categoryCounts();
        for (var type : AssetType.values()) {
            long count = switch (type) {
                case TABLE -> stats.tables();
                case MODEL -> stats.models();
                case METRIC -> stats.metrics();
                case SERVICE -> stats.services();
            };
            var categoryParams = listParams(filters, page);
            categoryParams.remove("assetCategory");
            categoryParams.put("assetType", type.name());
            categoryParams.put("pageNum", "1");
            var children = new ArrayList<Subcategory>();
            var groups = categoryCounts.stream().filter(group -> group.type() == type).toList();
            if (type == AssetType.MODEL) {
                for (String layer : AssetFilters.MODEL_LAYERS)
                    children.add(subcategory(layer, layer, groupCount(groups, layer), categoryParams));
            } else if (type == AssetType.METRIC) {
                for (var metric : MetricType.values())
                    children.add(subcategory(metric.name(), metric.getLabel(), groupCount(groups, metric.name()), categoryParams));
            } else {
                @SuppressWarnings("unchecked")
                var labels = (Map<String, String>) model.getAttribute("subjectAreaLabels");
                for (var group : groups) {
                    String label = type == AssetType.SERVICE && group.key().startsWith("area:")
                        ? labels.getOrDefault(group.key().substring(5), group.label()) : group.label();
                    children.add(subcategory(group.key(), label, group.count(), categoryParams));
                }
            }
            categories.add(new Category(type.name(), type.getLabel(), count, path("/assets", categoryParams), children));
        }
        model.addAttribute("categories", categories);
        var rootParams = listParams(filters, page);
        rootParams.remove("assetType");
        rootParams.remove("assetCategory");
        rootParams.put("pageNum", "1");
        model.addAttribute("treeRootPath", path("/assets", rootParams));
        model.addAttribute("resetPath", path("/assets", Map.of("assetType", filters.assetType(), "assetCategory", filters.assetCategory(),
            "pageSize", Integer.toString(page.pageSize()))));
        var editParams = returnParams(listParams(filters, page));
        var editPaths = new HashMap<String, String>();
        for (var asset : assets) editPaths.put(asset.getPath(), path(asset.getPath() + "/edit", editParams));
        model.addAttribute("editPaths", editPaths);
        return "assets";
    }

    @GetMapping("/{type}/{id}")
    String detail(@PathVariable String type, @PathVariable long id, Model model) {
        var asset = catalog.require(type, id);
        var profile = catalog.profile(asset);
        model.addAttribute("asset", asset);
        model.addAttribute("columns", catalog.columns(asset));
        model.addAttribute("qualityTasks", catalog.qualityLinks(asset));
        model.addAttribute("qualityTaskCount", catalog.qualityCount(asset));
        model.addAttribute("profileUpdatedAt", profile == null ? null : profile.getUpdatedAt());
        model.addAttribute("profileUpdatedBy", profile == null ? null : profile.getUpdatedBy());
        return "asset-detail";
    }

    @GetMapping("/{type}/{id}/edit")
    String edit(@PathVariable String type, @PathVariable long id, @RequestParam Map<String, String> params, Model model) {
        var asset = catalog.require(type, id);
        navigation(model, asset, params);
        var profile = catalog.profile(asset);
        var draft = profile == null ? new AssetProfile() : profile;
        if (asset.getType() == AssetType.SERVICE) {
            draft.setSubjectArea(asset.getSubjectArea());
            draft.setDescription(asset.getDescription());
        }
        return form(model, asset, draft);
    }

    @PostMapping("/{type}/{id}")
    String update(@PathVariable String type, @PathVariable long id, @ModelAttribute("draft") AssetProfile draft,
                  BindingResult errors, @RequestParam Map<String, String> params, Model model, Principal principal, RedirectAttributes redirect) {
        var asset = catalog.require(type, id);
        navigation(model, asset, params);
        try {
            if (errors.hasErrors()) throw new IllegalArgumentException("表单格式无效，请检查版本和字段内容");
            catalog.save(type, id, draft, principal.getName());
            redirect.addFlashAttribute("success", "资产描述已保存");
            return "redirect:" + model.getAttribute("backPath");
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return form(model, asset, draft);
        }
    }

    private static Map<String, String> listParams(AssetFilters filters, QueryPage page) {
        var params = new LinkedHashMap<String, String>();
        params.put("keyword", filters.keyword());
        params.put("assetType", filters.assetType());
        params.put("assetCategory", filters.assetCategory());
        params.put("subjectArea", filters.subjectArea());
        params.put("owner", filters.owner());
        params.put("tag", filters.tag());
        params.put("assetStatus", filters.status());
        params.put("pageNum", Integer.toString(page.pageNum()));
        params.put("pageSize", Integer.toString(page.pageSize()));
        params.values().removeIf(String::isEmpty);
        return params;
    }

    private static long groupCount(List<AssetCatalogService.CategoryCount> groups, String key) {
        return groups.stream().filter(group -> key.equals(group.key())).mapToLong(AssetCatalogService.CategoryCount::count).sum();
    }

    private static Subcategory subcategory(String key, String label, long count, Map<String, String> parentParams) {
        var params = new LinkedHashMap<>(parentParams);
        params.put("assetCategory", key);
        return new Subcategory(key, label, count, path("/assets", params));
    }

    private static Map<String, String> returnParams(Map<String, String> listParams) {
        var params = new LinkedHashMap<String, String>();
        params.put("returnTo", "list");
        listParams.forEach((key, value) -> params.put("return_" + key, value));
        return params;
    }

    private static void navigation(Model model, AssetEntry asset, Map<String, String> params) {
        boolean detail = "detail".equals(params.get("returnTo"));
        var listParams = new LinkedHashMap<String, String>();
        for (String key : FILTER_KEYS) {
            if (params.containsKey("return_" + key)) listParams.put(key, params.get("return_" + key));
        }
        for (String key : List.of("pageNum", "pageSize")) {
            if (params.containsKey("return_" + key)) listParams.put(key, params.get("return_" + key));
        }
        var filters = AssetFilters.from(listParams);
        var page = QueryPage.parse(listParams, 20);
        var normalized = listParams.isEmpty() ? Map.<String, String>of() : listParams(filters, page);
        String listPath = path("/assets", normalized);
        model.addAttribute("returnTo", detail ? "detail" : "list");
        model.addAttribute("listReturnPath", listPath);
        model.addAttribute("backPath", detail ? asset.getPath() : listPath);
        model.addAttribute("returnParams", detail ? Map.of("returnTo", "detail") : returnParams(normalized));
    }

    private static String path(String base, Map<String, String> params) {
        var builder = UriComponentsBuilder.fromPath(base);
        params.forEach((key, value) -> {
            if (!value.isEmpty()) builder.queryParam(key, "{" + key + "}");
        });
        return builder.encode().buildAndExpand(params).toUriString();
    }

    private String form(Model model, AssetEntry asset, AssetProfile draft) {
        model.addAttribute("asset", asset);
        model.addAttribute("draft", draft);
        model.addAttribute("sensitivityOptions", AssetCatalogService.SENSITIVITIES);
        return "asset-form";
    }
}
