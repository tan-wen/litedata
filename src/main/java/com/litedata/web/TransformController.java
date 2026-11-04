package com.litedata.web;

import com.litedata.model.SqlTask;
import com.litedata.service.QueryPage;
import com.litedata.store.PlatformMapper;
import com.litedata.modeling.ModelingService;
import com.litedata.transform.*;
import com.litedata.workflow.WorkflowService;
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
@RequestMapping("/transforms")
public class TransformController {
    private final TransformService tasks;
    private final ModelingService models;
    private final WorkflowService workflows;
    public TransformController(TransformService tasks,ModelingService models,WorkflowService workflows) {
        this.tasks=tasks; this.models=models;
        this.workflows=workflows;
    }
    @ModelAttribute("workflowId") Long context(@RequestParam(required=false) Long workflowId) {
        if(workflowId!=null) workflows.require(workflowId); return workflowId;
    }
    @ModelAttribute("activeNav") String activeNav() { return "workflows"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest request) { return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean canManageUsers(HttpServletRequest request) { return request.isUserInRole("ADMIN"); }
    @ModelAttribute void navigation(@RequestParam Map<String,String> params,Model model) {
        // Accept a source marker, never a client-provided redirect URL.
        String returnTo="detail".equals(params.get("returnTo"))?"detail":"list";
        model.addAttribute("returnTo",returnTo);
        String listPath="/transforms";
        if("list".equals(returnTo) && (params.containsKey("pageNum") || params.containsKey("pageSize"))) {
            var page=QueryPage.parse(params,20);
            model.addAttribute("returnPageNum",page.pageNum()); model.addAttribute("returnPageSize",page.pageSize());
            listPath+="?pageNum="+page.pageNum()+"&pageSize="+page.pageSize();
        }
        model.addAttribute("listReturnPath",listPath);
    }
    @InitBinder("draft") void bind(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(100);
        binder.setAllowedFields("name","modelId","sqlText","version");
    }
    @GetMapping String list(@RequestParam Map<String,String> params, Model model) {
        var page = ListPagination.prepare(model, "/transforms", params, tasks.count());
        model.addAttribute("tasks", tasks.list(page));
        return "transforms";
    }
    @GetMapping("/new") String create(Model model) { return form(model,new SqlTask(),null); }
    @GetMapping("/{id}/edit") String edit(@PathVariable long id,Model model) { return form(model,tasks.require(id),id); }
    @GetMapping("/{id}") String detail(@PathVariable long id,Model model) {
        model.addAttribute("task",tasks.require(id)); model.addAttribute("runs",tasks.runs(id));
        model.addAttribute("taskWorkflows",workflows.forTask(id));
        return "transform-detail";
    }
    @PostMapping String create(@ModelAttribute("draft") SqlTask draft,BindingResult errors,Model model,Principal principal,RedirectAttributes redirect,HttpServletRequest request) {
        return save(null,draft,errors,model,principal,redirect,request);
    }
    @PostMapping("/{id}") String update(@PathVariable long id,@ModelAttribute("draft") SqlTask draft,BindingResult errors,Model model,Principal principal,RedirectAttributes redirect,HttpServletRequest request) {
        return save(id,draft,errors,model,principal,redirect,request);
    }
    private String save(Long id,SqlTask draft,BindingResult errors,Model model,Principal principal,RedirectAttributes redirect,HttpServletRequest request) {
        try {
            if(errors.hasErrors()) throw new IllegalArgumentException("表单格式有误，请检查模型和上游任务选择");
            // The standalone editor now edits SQL only. Omitted graph fields must never clear dependencies.
            if(id!=null) {
                var current=tasks.require(id);
                draft.setUpstreamTaskIds(current.getUpstreamTaskIds());
                draft.setUpstreamPipelineIds(current.getUpstreamPipelineIds());
            }
            if(model.getAttribute("workflowId") instanceof Long workflowId) draft.setWorkflowId(workflowId);
            tasks.save(id,draft,principal.getName());
            redirect.addFlashAttribute("success","SQL 任务已保存，依赖和执行周期请在数据加工中维护");
            return "redirect:"+returnPath(model,id);
        } catch(IllegalArgumentException exception) {
            model.addAttribute("error",exception.getMessage()); return form(model,draft,id);
        } catch(org.springframework.dao.DuplicateKeyException exception) {
            model.addAttribute("error","任务名称或目标模型已被其他有效 SQL 任务使用"); return form(model,draft,id);
        }
    }
    @PostMapping("/{id}/archive") String archive(@PathVariable long id,@RequestParam int version,Principal principal,RedirectAttributes redirect) {
        tasks.archive(id,version,principal.getName()); redirect.addFlashAttribute("success","SQL 任务已归档，保留物理数据和运行记录"); return "redirect:/transforms";
    }
    private String form(Model model,SqlTask draft,Long id) {
        model.addAttribute("draft",draft); model.addAttribute("taskId",id);
        model.addAttribute("backPath",returnPath(model,id));
        model.addAttribute("models",models.list("","").stream().filter(m->!"ODS".equals(m.getLayer())).toList());
        return "transform-form";
    }
    private String returnPath(Model model,Long id) {
        if(model.getAttribute("workflowId") instanceof Long workflowId) return "/workflows/"+workflowId;
        if(id!=null && "detail".equals(model.getAttribute("returnTo"))) return "/transforms/"+id;
        return Objects.toString(model.getAttribute("listReturnPath"),"/transforms");
    }
}
