package com.litedata.web;

import com.litedata.model.SqlTask;
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
    private final TransformScheduler scheduler;
    private final ModelingService models;
    private final PlatformMapper platform;
    private final WorkflowService workflows;
    public TransformController(TransformService tasks,TransformScheduler scheduler,ModelingService models,PlatformMapper platform,WorkflowService workflows) {
        this.tasks=tasks; this.scheduler=scheduler; this.models=models; this.platform=platform;
        this.workflows=workflows;
    }
    @ModelAttribute("workflowId") Long context(@RequestParam(required=false) Long workflowId) {
        if(workflowId!=null) workflows.require(workflowId); return workflowId;
    }
    @ModelAttribute("activeNav") String activeNav() { return "transforms"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest request) { return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean canManageUsers(HttpServletRequest request) { return request.isUserInRole("ADMIN"); }
    @InitBinder("draft") void bind(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(100);
        binder.setAllowedFields("name","modelId","sqlText","cronExpression","version","upstreamTaskIds","upstreamPipelineIds");
    }
    @GetMapping String list(@RequestParam Map<String,String> params, Model model) {
        var page = ListPagination.prepare(model, "/transforms", params, tasks.count());
        model.addAttribute("tasks", tasks.list(page));
        return "transforms";
    }
    @GetMapping("/new") String create(Model model) { return form(model,new SqlTask(),null); }
    @GetMapping("/{id}/edit") String edit(@PathVariable long id,Model model) { return form(model,tasks.require(id),id); }
    @GetMapping("/{id}") String detail(@PathVariable long id,Model model) {
        var task=tasks.require(id); var graph=tasks.graph(id);
        model.addAttribute("task",task); model.addAttribute("graph",graph); model.addAttribute("runs",tasks.runs(id));
        model.addAttribute("taskWorkflow",workflows.forRoot(id));
        var names=new LinkedHashMap<String,String>(); graph.nodes().forEach(node->names.put(node.key(),node.name()));
        model.addAttribute("nodeNames",names);
        model.addAttribute("definitionCurrent",Objects.equals(task.getDefinitionHash(),graph.hash()));
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
                if(!request.getParameterMap().containsKey("cronExpression")) draft.setCronExpression(current.getCronExpression());
                if(!request.getParameterMap().containsKey("upstreamTaskIds") && !request.getParameterMap().containsKey("_upstreamTaskIds")) draft.setUpstreamTaskIds(current.getUpstreamTaskIds());
                if(!request.getParameterMap().containsKey("upstreamPipelineIds") && !request.getParameterMap().containsKey("_upstreamPipelineIds")) draft.setUpstreamPipelineIds(current.getUpstreamPipelineIds());
            }
            long saved=tasks.save(id,draft,principal.getName());
            redirect.addFlashAttribute("success","数据加工已保存，依赖和执行周期请在工作流中维护");
            if(model.getAttribute("workflowId") instanceof Long workflowId) return "redirect:/workflows/"+workflowId;
            return "redirect:/transforms/"+saved;
        } catch(IllegalArgumentException exception) {
            model.addAttribute("error",exception.getMessage()); return form(model,draft,id);
        } catch(org.springframework.dao.DuplicateKeyException exception) {
            model.addAttribute("error","任务名称或目标模型已被其他任务使用（含已归档任务）"); return form(model,draft,id);
        }
    }
    @PostMapping("/{id}/run") String run(@PathVariable long id,@RequestParam int version,Principal principal,RedirectAttributes redirect) {
        scheduler.run(id,version,principal.getName());
        redirect.addFlashAttribute("success","DolphinScheduler 已接受整条依赖链，刷新查看实际节点运行结果"); return "redirect:/transforms/"+id;
    }
    @PostMapping("/{id}/schedule") String schedule(@PathVariable long id,@RequestParam int version,Principal principal,RedirectAttributes redirect) {
        scheduler.schedule(id,version,principal.getName()); redirect.addFlashAttribute("success","整条依赖链的定时调度已启用"); return "redirect:/transforms/"+id;
    }
    @PostMapping("/{id}/pause") String pause(@PathVariable long id,@RequestParam int version,Principal principal,RedirectAttributes redirect) {
        scheduler.pause(id,version,principal.getName()); redirect.addFlashAttribute("success","定时调度已停用；已提交的实例继续执行"); return "redirect:/transforms/"+id;
    }
    @PostMapping("/{id}/archive") String archive(@PathVariable long id,@RequestParam int version,Principal principal,RedirectAttributes redirect) {
        tasks.archive(id,version,principal.getName()); redirect.addFlashAttribute("success","加工任务已归档，保留物理数据和运行记录"); return "redirect:/transforms";
    }
    private String form(Model model,SqlTask draft,Long id) {
        model.addAttribute("draft",draft); model.addAttribute("taskId",id); model.addAttribute("schedules",TransformService.SCHEDULES);
        model.addAttribute("models",models.list("","").stream().filter(m->!"ODS".equals(m.getLayer())).toList());
        model.addAttribute("upstreamTasks",tasks.list().stream().filter(t->!Objects.equals(t.getId(),id)).toList());
        model.addAttribute("pipelines",platform.pipelines()); return "transform-form";
    }
}
