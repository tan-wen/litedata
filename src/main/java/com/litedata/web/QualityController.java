package com.litedata.web;

import com.litedata.quality.*;
import com.litedata.store.PlatformMapper;
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
@RequestMapping("/quality")
public class QualityController {
    public record SourceOption(Long id,String name) {}
    private final QualityService quality;
    private final PlatformMapper platform;
    public QualityController(QualityService quality,PlatformMapper platform) { this.quality=quality; this.platform=platform; }
    @ModelAttribute("activeNav") String activeNav() { return "quality"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest r) { return r.isUserInRole("ADMIN") || r.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean canManageUsers(HttpServletRequest r) { return r.isUserInRole("ADMIN"); }
    @InitBinder("draft") void bind(WebDataBinder b) {
        b.setAutoGrowCollectionLimit(30);
        b.setAllowedFields("name","sourceId","tableName","owner","description","cronExpression","timeColumn","windowHours","timeoutSeconds","version",
                "rules[*].name","rules[*].type","rules[*].columns","rules[*].parameter1","rules[*].parameter2","rules[*].referenceTable","rules[*].referenceColumn","rules[*].toleranceCount","rules[*].tolerancePercent","rules[*].severity");
    }
    @ExceptionHandler(org.springframework.beans.InvalidPropertyException.class)
    org.springframework.http.ResponseEntity<String> invalidRuleIndex() {
        return org.springframework.http.ResponseEntity.badRequest().body("规则索引无效，每个任务最多30条规则");
    }
    @GetMapping String list(@RequestParam(defaultValue="") String keyword,@RequestParam Map<String,String> params,Model m) {
        keyword=quality.keyword(keyword); var page=ListPagination.prepare(m,"/quality",params,quality.count(keyword));
        m.addAttribute("tasks",quality.list(keyword,page)); m.addAttribute("keyword",keyword); return "quality";
    }
    @GetMapping("/new") String create(Model m) { var t=new QualityTask(); t.getRules().add(new QualityRule()); return form(m,t,null); }
    @GetMapping("/{id}/edit") String edit(@PathVariable long id,Model m) { return form(m,quality.require(id),id); }
    @GetMapping("/{id}") String detail(@PathVariable long id,@RequestParam Map<String,String> params,Model m) {
        var t=quality.require(id); m.addAttribute("task",t); m.addAttribute("rules",t.getRules());
        var page=ListPagination.prepare(m,"/quality/"+id,params,quality.runCount(id)); m.addAttribute("runs",quality.runs(id,page)); return "quality-detail";
    }
    @GetMapping("/runs/{id}") String runDetail(@PathVariable long id,Model m) {
        var r=quality.run(id); m.addAttribute("run",r); m.addAttribute("task",quality.require(r.getTaskId())); m.addAttribute("results",quality.results(id)); return "quality-run-detail";
    }
    @PostMapping String create(@ModelAttribute("draft") QualityTask draft,BindingResult errors,Model m,Principal p,RedirectAttributes r) { return save(null,draft,errors,m,p,r); }
    @PostMapping("/{id}") String update(@PathVariable long id,@ModelAttribute("draft") QualityTask draft,BindingResult errors,Model m,Principal p,RedirectAttributes r) { return save(id,draft,errors,m,p,r); }
    private String save(Long id,QualityTask draft,BindingResult errors,Model m,Principal p,RedirectAttributes r) {
        try {
            if(errors.hasErrors()) throw new IllegalArgumentException("表单格式有误，请检查规则类型、数值、数据源和字段配置");
            quality.save(id,draft,p.getName()); r.addFlashAttribute("success","质量任务已保存；修改配置会暂停原有定时调度，请按需重新启用"); return "redirect:/quality";
        } catch(IllegalArgumentException e) { m.addAttribute("error",e.getMessage()); return form(m,draft,id); }
        catch(org.springframework.dao.DuplicateKeyException e) { m.addAttribute("error","质量任务名称已存在（含已归档任务），请使用其他名称"); return form(m,draft,id); }
    }
    @PostMapping("/{id}/run") String start(@PathVariable long id,@RequestParam int version,Principal p) { return "redirect:/quality/runs/"+quality.start(id,version,p.getName()); }
    @PostMapping("/{id}/schedule") String schedule(@PathVariable long id,@RequestParam int version,Principal p,RedirectAttributes r) {
        quality.schedule(id,version,true,p.getName()); r.addFlashAttribute("success","定时扫描已启用，使用北京时间；平台在线时运行"); return "redirect:/quality/"+id;
    }
    @PostMapping("/{id}/pause") String pause(@PathVariable long id,@RequestParam int version,Principal p,RedirectAttributes r) {
        quality.schedule(id,version,false,p.getName()); r.addFlashAttribute("success","定时扫描已停用"); return "redirect:/quality/"+id;
    }
    @PostMapping("/{id}/archive") String archive(@PathVariable long id,@RequestParam int version,Principal p,RedirectAttributes r) {
        quality.archive(id,version,p.getName()); r.addFlashAttribute("success","任务已归档，历史配置与扫描结果保留"); return "redirect:/quality";
    }
    private String form(Model m,QualityTask draft,Long id) {
        m.addAttribute("draft",draft); m.addAttribute("taskId",id); m.addAttribute("ruleTypes",RuleType.values());
        m.addAttribute("sources",platform.sources().stream().map(s->new SourceOption(s.getId(),s.getName())).toList()); return "quality-form";
    }
}
