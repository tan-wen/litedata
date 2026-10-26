package com.litedata.web;

import com.litedata.model.DataModel;
import com.litedata.model.ModelField;
import com.litedata.modeling.ModelingService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/models")
public class ModelingController {
    private final ModelingService modeling;
    public ModelingController(ModelingService modeling) { this.modeling = modeling; }

    @InitBinder("draft")
    void bind(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(100);
        binder.setAllowedFields("tableName", "name", "subjectArea", "layer", "description", "buckets", "version",
                "dimensionKeys", "dimensionLabel", "dimensionStrategy",
                "fields[*].name", "fields[*].dataType", "fields[*].keyColumn", "fields[*].nullable", "fields[*].comment", "fields[*].standard");
    }
    @ModelAttribute("activeNav") String activeNav() { return "models"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest request) {
        return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER");
    }
    @ModelAttribute("canManageUsers") boolean canManageUsers(HttpServletRequest request) { return request.isUserInRole("ADMIN"); }
    @ModelAttribute("layers") List<String> layers() { return List.of("ODS", "DIM", "DWD", "DWS", "ADS"); }

    @GetMapping String list(@RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="") String layer,
            @RequestParam Map<String,String> params, Model model) {
        var page = ListPagination.prepare(model, "/models", params, modeling.count(keyword, layer));
        model.addAttribute("models", modeling.list(keyword, layer, page));
        model.addAttribute("keyword", keyword); model.addAttribute("layer", layer);
        return "models";
    }
    @GetMapping("/new") String create(@RequestParam(defaultValue="ODS") String layer, Model model) {
        var draft = new DataModel();
        var field = new ModelField(); field.setName("id"); field.setDataType("BIGINT"); field.setKeyColumn(true); field.setComment("业务主键");
        draft.getFields().add(field);
        if ("DIM".equals(layer)) {
            draft.setLayer("DIM"); draft.setDimensionKeys("id"); draft.setDimensionLabel("name"); draft.setDimensionStrategy("SCD1");
            var label = new ModelField(); label.setName("name"); label.setDataType("VARCHAR(128)"); label.setComment("维度名称");
            draft.getFields().add(label);
        }
        return form(model, draft, null);
    }
    @GetMapping("/{id}/edit") String edit(@PathVariable long id, Model model) { return form(model, modeling.require(id), id); }
    @GetMapping("/{id}") String detail(@PathVariable long id, Model model) {
        var preview = modeling.preview(id);
        model.addAttribute("definition", preview.model()); model.addAttribute("ddl", preview.ddl()); model.addAttribute("ddlHash", preview.hash());
        model.addAttribute("executions", modeling.executions(id));
        return "model-detail";
    }
    @PostMapping String create(@ModelAttribute("draft") DataModel draft, BindingResult errors, Model model, Principal principal, RedirectAttributes redirect) {
        return save(null, draft, errors, model, principal, redirect);
    }
    @PostMapping("/{id}") String update(@PathVariable long id, @ModelAttribute("draft") DataModel draft, BindingResult errors,
            Model model, Principal principal, RedirectAttributes redirect) {
        return save(id, draft, errors, model, principal, redirect);
    }
    private String save(Long id, DataModel draft, BindingResult errors, Model model, Principal principal, RedirectAttributes redirect) {
        try {
            if (errors.hasErrors()) throw new IllegalArgumentException("字段格式有误，请检查分桶数、版本和字段定义");
            long saved = modeling.save(id, draft, principal.getName());
            redirect.addFlashAttribute("success", "逻辑模型已保存，请预览 DDL 并核对物理结构");
            return "redirect:/models/" + saved;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return form(model, draft, id);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            model.addAttribute("error", "表名已被模型使用（含已归档模型），请选择其他表名");
            return form(model, draft, id);
        }
    }
    @PostMapping("/{id}/physicalize") String physicalize(@PathVariable long id, @RequestParam int version, @RequestParam String ddlHash,
            @RequestParam(defaultValue="false") boolean confirmed, Principal principal, RedirectAttributes redirect) {
        if (!confirmed) throw new IllegalArgumentException("请在预览 DDL 后勾选建表确认");
        var result = modeling.physicalize(id, version, ddlHash, principal.getName());
        redirect.addFlashAttribute(result.success() ? "success" : "error", result.message());
        return "redirect:/models/" + id;
    }
    @PostMapping("/{id}/compare") String compare(@PathVariable long id, @RequestParam int version, Principal principal, RedirectAttributes redirect) {
        var comparison = modeling.compare(id, version, principal.getName());
        redirect.addFlashAttribute("comparison", comparison);
        redirect.addFlashAttribute(comparison.matches() ? "success" : "error", comparison.matches() ? "逻辑模型与物理结构一致" : "核对未通过，请查看结构比对结果");
        return "redirect:/models/" + id;
    }
    @PostMapping("/{id}/archive") String archive(@PathVariable long id, @RequestParam int version, Principal principal, RedirectAttributes redirect) {
        modeling.archive(id, version, principal.getName());
        redirect.addFlashAttribute("success", "逻辑模型已归档，物理表及执行记录已保留");
        return "redirect:/models";
    }
    private String form(Model model, DataModel draft, Long id) {
        model.addAttribute("draft", draft); model.addAttribute("modelId", id);
        return "model-form";
    }
}
