package com.litedata.web;

import com.litedata.metric.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/metrics")
public class MetricController {
    private final MetricService metrics;
    private final MetricLineageService lineage;
    public MetricController(MetricService metrics, MetricLineageService lineage) { this.metrics = metrics; this.lineage = lineage; }
    @ModelAttribute("activeNav") String activeNav() { return "metrics"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest r) { return r.isUserInRole("ADMIN") || r.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean canManageUsers(HttpServletRequest r) { return r.isUserInRole("ADMIN"); }
    @ModelAttribute("metricTypes") MetricType[] metricTypes() { return MetricType.values(); }
    @InitBinder("draft") void bind(WebDataBinder binder) {
        binder.setAllowedFields("code", "name", "type", "subjectArea", "owner", "unit", "description", "dimensions", "modelId",
                "aggregation", "measureColumn", "timeColumn", "baseMetricId", "windowDays", "filterExpression", "formula", "version");
    }
    @GetMapping String list(@RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="") String metricType,
                           @RequestParam Map<String,String> params, Model model) {
        keyword = metrics.keyword(keyword); metricType = metrics.type(metricType);
        var page = ListPagination.prepare(model, "/metrics", params, metrics.count(keyword, metricType));
        model.addAttribute("metrics", metrics.list(keyword, metricType, page));
        model.addAttribute("keyword", keyword); model.addAttribute("metricType", metricType); return "metrics";
    }
    @GetMapping("/new") String create(@RequestParam(defaultValue="ATOMIC") String type, Model model) {
        var draft = new MetricDefinition();
        if (metrics.type(type).isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "指标类型无效");
        draft.setType(MetricType.valueOf(type)); return form(model, draft, null);
    }
    @GetMapping("/{id}/edit") String edit(@PathVariable long id, Model model) { return form(model, metrics.require(id), id); }
    @GetMapping("/{id}") String detail(@PathVariable long id, @RequestParam Map<String,String> params, Model model) {
        return detail(model, metrics.require(id), false, params);
    }
    @GetMapping("/{id}/versions/{version}") String history(@PathVariable long id, @PathVariable int version, @RequestParam Map<String,String> params, Model model) {
        return detail(model, metrics.historyDefinition(id, version), true, params);
    }
    @GetMapping("/{id}/lineage") String lineage(@PathVariable long id, @RequestParam(defaultValue="metric_value") String field,
            @RequestParam(defaultValue="direct") String mode, Model model) {
        return lineage(model, metrics.require(id), field, mode, false);
    }
    @GetMapping("/{id}/versions/{version}/lineage") String historicalLineage(@PathVariable long id, @PathVariable int version,
            @RequestParam(defaultValue="metric_value") String field, @RequestParam(defaultValue="direct") String mode, Model model) {
        return lineage(model, metrics.historyDefinition(id, version), field, mode, true);
    }
    @PostMapping String create(@ModelAttribute("draft") MetricDefinition draft, BindingResult errors, Model model, Principal principal, RedirectAttributes redirect) {
        return save(null, draft, errors, model, principal, redirect);
    }
    @PostMapping("/{id}") String update(@PathVariable long id, @ModelAttribute("draft") MetricDefinition draft, BindingResult errors,
                                       Model model, Principal principal, RedirectAttributes redirect) {
        return save(id, draft, errors, model, principal, redirect);
    }
    private String save(Long id, MetricDefinition draft, BindingResult errors, Model model, Principal principal, RedirectAttributes redirect) {
        try {
            if (errors.hasErrors()) throw new IllegalArgumentException("字段格式有误，请检查类型、来源模型、窗口天数及版本");
            metrics.save(id, draft, principal.getName());
            redirect.addFlashAttribute("success", "指标已保存，口径、依赖版本和 SQL 预览已记录"); return "redirect:/metrics";
        } catch (IllegalArgumentException e) { model.addAttribute("error", e.getMessage()); return form(model, draft, id); }
        catch (org.springframework.dao.DuplicateKeyException e) { model.addAttribute("error", "指标编码已存在（含已归档指标），请使用其他编码"); return form(model, draft, id); }
    }
    @PostMapping("/{id}/archive") String archive(@PathVariable long id, @RequestParam int version, Principal principal, RedirectAttributes redirect) {
        metrics.archive(id, version, principal.getName()); redirect.addFlashAttribute("success", "指标已归档，历史口径和 SQL 已保留"); return "redirect:/metrics";
    }
    private String form(Model model, MetricDefinition draft, Long id) {
        model.addAttribute("draft", draft); model.addAttribute("metricId", id);
        model.addAttribute("models", metrics.modelOptions()); model.addAttribute("candidates", metrics.candidates(id)); return "metric-form";
    }
    private String detail(Model model, MetricDefinition definition, boolean historical, Map<String,String> params) {
        model.addAttribute("definition", definition); model.addAttribute("sql", definition.getSqlText());
        model.addAttribute("dependencies", definition.getDependencies()); model.addAttribute("dependents", metrics.dependents(definition.getId()));
        model.addAttribute("historical", historical);
        String path = "/metrics/" + definition.getId() + (historical ? "/versions/" + definition.getVersion() : "");
        var page = ListPagination.prepare(model, path, params, metrics.versionCount(definition.getId()));
        model.addAttribute("history", metrics.versions(definition.getId(), page)); return "metric-detail";
    }
    private String lineage(Model model, MetricDefinition definition, String field, String mode, boolean historical) {
        if (!mode.equals("direct") && !mode.equals("full"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "血缘展示范围无效");
        String path = "/metrics/" + definition.getId() + (historical ? "/versions/" + definition.getVersion() : "");
        model.addAttribute("definition", definition); model.addAttribute("field", field);
        var projection = MetricLineageProjection.project(lineage.view(definition, field), mode);
        model.addAttribute("graph", projection.graph()); model.addAttribute("projection", projection); model.addAttribute("mode", mode);
        model.addAttribute("backPath", path); model.addAttribute("lineagePath", path + "/lineage"); return "metric-lineage";
    }
}
