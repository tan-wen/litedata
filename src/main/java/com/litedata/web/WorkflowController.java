package com.litedata.web;

import com.litedata.service.QueryPage;
import com.litedata.transform.TransformService;
import com.litedata.workflow.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/workflows")
public class WorkflowController {
    private final WorkflowService workflows;
    private final TransformService tasks;
    public WorkflowController(WorkflowService workflows,TransformService tasks) { this.workflows=workflows; this.tasks=tasks; }
    @ModelAttribute("activeNav") String nav() { return "workflows"; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest request) { return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean admin(HttpServletRequest request) { return request.isUserInRole("ADMIN"); }
    @InitBinder("draft") void bind(WebDataBinder binder) { binder.setAllowedFields("name","description","rootTaskId","version"); }
    @GetMapping String list(@RequestParam Map<String,String> params,Model model) {
        String keyword=params.getOrDefault("keyword",""); model.addAttribute("keyword",keyword);
        var page=ListPagination.prepare(model,"/workflows",params,workflows.count(keyword));
        model.addAttribute("workflows",workflows.list(keyword,page)); return "workflows";
    }
    @GetMapping("/new") String create(@RequestParam(required=false) Long rootTaskId,Model model) {
        var draft=new ProcessingWorkflow();
        if(rootTaskId!=null) { var task=tasks.require(rootTaskId); draft.setRootTaskId(rootTaskId); draft.setName(task.getName()); }
        return form(model,draft,null);
    }
    @GetMapping("/{id}/edit") String edit(@PathVariable long id,Model model) { return form(model,workflows.require(id),id); }
    @GetMapping("/{id}") String canvas(@PathVariable long id,Model model) {
        var workflow=workflows.summary(id); model.addAttribute("workflow",workflow); model.addAttribute("rootTask",tasks.require(workflow.rootTaskId()));
        model.addAttribute("schedules",TransformService.SCHEDULES); return "workflow-canvas";
    }
    @PostMapping String create(@ModelAttribute("draft") ProcessingWorkflow draft,BindingResult errors,Model model,Principal principal,RedirectAttributes redirect) {
        return metadata(null,draft,errors,model,principal,redirect);
    }
    @PostMapping("/{id}/metadata") String update(@PathVariable long id,@ModelAttribute("draft") ProcessingWorkflow draft,BindingResult errors,Model model,Principal principal,RedirectAttributes redirect) {
        return metadata(id,draft,errors,model,principal,redirect);
    }
    private String metadata(Long id,ProcessingWorkflow draft,BindingResult errors,Model model,Principal principal,RedirectAttributes redirect) {
        try {
            if(errors.hasErrors()) throw new IllegalArgumentException("工作流表单格式有误");
            long saved=workflows.metadata(id,draft,principal.getName()); redirect.addFlashAttribute("success","工作流已保存，请在画布中编排依赖");
            return "redirect:/workflows/"+saved;
        } catch(IllegalArgumentException e) { model.addAttribute("error",e.getMessage()); }
        catch(org.springframework.dao.DuplicateKeyException e) { model.addAttribute("error","工作流名称或末端任务已被使用，请打开现有工作流"); }
        return form(model,draft,id);
    }
    private String form(Model model,ProcessingWorkflow draft,Long id) {
        if(id!=null) draft.setRootTaskId(workflows.require(id).getRootTaskId());
        model.addAttribute("draft",draft); model.addAttribute("workflowId",id); model.addAttribute("roots",workflows.roots()); return "workflow-form";
    }
    @GetMapping("/{id}/graph") @ResponseBody ResponseEntity<?> graph(@PathVariable long id) { return json(workflows.view(id)); }
    @PostMapping("/{id}/graph") @ResponseBody ResponseEntity<?> save(@PathVariable long id,@RequestBody WorkflowService.Save input,Principal principal) {
        return json(workflows.save(id,input,principal.getName()));
    }
    @GetMapping("/{id}/catalog") @ResponseBody ResponseEntity<?> catalog(@PathVariable long id,@RequestParam Map<String,String> params) {
        var page=QueryPage.parse(params,10);
        if(page.pageSize()>100) throw new IllegalArgumentException("节点目录每页最多100条");
        return json(workflows.catalog(id,params.getOrDefault("keyword",""),page));
    }
    @GetMapping("/{id}/expand") @ResponseBody ResponseEntity<?> expand(@PathVariable long id,@RequestParam String key) {
        return json(Map.of("nodes",workflows.expand(id,key)));
    }
    @PostMapping("/{id}/{action:run|schedule|pause}") String execute(@PathVariable long id,@PathVariable String action,@RequestParam int version,
            @RequestParam(defaultValue="") String graphHash,Principal principal,RedirectAttributes redirect) {
        workflows.execute(id,version,graphHash,action,principal.getName());
        redirect.addFlashAttribute("success",switch(action) { case "run" -> "已提交整条工作流，请进入运行实例查看结果"; case "schedule" -> "工作流定时调度已启用"; default -> "工作流定时调度已停用，已提交实例继续执行"; });
        return "redirect:/workflows/"+id;
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody ResponseEntity<?> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("message",Objects.toString(e.getReason(),"请求失败")));
    }
    private static ResponseEntity<?> json(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
