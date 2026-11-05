package com.litedata.web;

import com.litedata.service.QueryPage;
import com.litedata.modeling.ModelingService;
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
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@RequestMapping("/workflows")
public class WorkflowController {
    private final WorkflowService workflows;
    private final ModelingService models;

    public WorkflowController(WorkflowService workflows, ModelingService models) {
        this.workflows = workflows;
        this.models = models;
    }

    @ModelAttribute("activeNav")
    String nav() {
        return "workflows";
    }

    @ModelAttribute("canEdit")
    boolean canEdit(HttpServletRequest request) {
        return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER");
    }

    @ModelAttribute("canManageUsers")
    boolean admin(HttpServletRequest request) {
        return request.isUserInRole("ADMIN");
    }

    @InitBinder("draft")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("name", "description", "version");
    }

    @GetMapping
    String list(@RequestParam Map<String, String> params, Model model) {
        String keyword = params.getOrDefault("keyword", "");
        model.addAttribute("keyword", keyword);
        var page = ListPagination.prepare(model, "/workflows", params, workflows.count(keyword));
        var items = workflows.list(keyword, page);
        items.forEach(item -> item.setLastRunAt(DisplayTime.beijing(item.getLastRunAt())));
        model.addAttribute("workflows", items);
        return "workflows";
    }

    @GetMapping("/new")
    String create(Model model) {
        return form(model, new ProcessingWorkflow(), null);
    }

    @GetMapping("/{id}/edit")
    String edit(@PathVariable long id, Model model) {
        return form(model, workflows.require(id), id);
    }

    @GetMapping("/{id}")
    String canvas(@PathVariable long id, Model model) {
        model.addAttribute("workflow", workflows.summary(id));
        model.addAttribute("sqlModels", workflows.modelChoices(models.list("", "")));
        return "workflow-canvas";
    }

    @PostMapping
    String create(@ModelAttribute("draft") ProcessingWorkflow draft, BindingResult errors, Model model, Principal principal, RedirectAttributes redirect) {
        return metadata(null, draft, errors, model, principal, redirect);
    }

    @PostMapping("/{id}/metadata")
    String update(@PathVariable long id, @ModelAttribute("draft") ProcessingWorkflow draft, BindingResult errors, Model model, Principal principal, RedirectAttributes redirect) {
        return metadata(id, draft, errors, model, principal, redirect);
    }

    private String metadata(Long id, ProcessingWorkflow draft, BindingResult errors, Model model, Principal principal, RedirectAttributes redirect) {
        try {
            if (errors.hasErrors()) throw new IllegalArgumentException("加工流程表单格式有误");
            long saved = workflows.metadata(id, draft, principal.getName());
            redirect.addFlashAttribute("success", "加工流程已保存，请在画布中编排依赖");
            return "redirect:/workflows/" + saved;
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            model.addAttribute("error", "加工流程名称已被使用，请打开现有加工流程");
        }
        return form(model, draft, id);
    }

    private String form(Model model, ProcessingWorkflow draft, Long id) {
        model.addAttribute("draft", draft);
        model.addAttribute("workflowId", id);
        return "workflow-form";
    }

    @GetMapping("/{id}/graph")
    @ResponseBody
    ResponseEntity<?> graph(@PathVariable long id) {
        return json(workflows.view(id));
    }

    @PostMapping("/{id}/graph")
    @ResponseBody
    ResponseEntity<?> save(@PathVariable long id, @RequestBody WorkflowService.Save input, Principal principal) {
        return json(workflows.save(id, input, principal.getName()));
    }

    @GetMapping("/{id}/sql-tasks/{taskId}")
    @ResponseBody
    ResponseEntity<?> task(@PathVariable long id, @PathVariable long taskId) {
        return json(workflows.task(id, taskId));
    }

    @GetMapping("/{id}/sql-models")
    @ResponseBody
    ResponseEntity<?> sqlModels(@PathVariable long id) {
        workflows.require(id);
        return json(workflows.modelChoices(models.list("", "")));
    }

    @PostMapping("/{id}/sql-tasks")
    @ResponseBody
    ResponseEntity<?> task(@PathVariable long id, @RequestBody WorkflowService.TaskSave input, Principal principal) {
        return json(workflows.saveTask(id, input, principal.getName()));
    }

    @GetMapping("/{id}/catalog")
    @ResponseBody
    ResponseEntity<?> catalog(@PathVariable long id, @RequestParam Map<String, String> params) {
        var page = QueryPage.parse(params, 10);
        if (page.pageSize() > 100) throw new IllegalArgumentException("节点目录每页最多100条");
        return json(workflows.catalog(id, params.getOrDefault("keyword", ""), page));
    }

    @GetMapping("/{id}/expand")
    @ResponseBody
    ResponseEntity<?> expand(@PathVariable long id, @RequestParam String key) {
        return json(Map.of("nodes", workflows.expand(id, key)));
    }

    @PostMapping("/{id}/{action:run|schedule|pause}")
    String execute(@PathVariable long id, @PathVariable String action, @RequestParam int version,
                   @RequestParam(defaultValue = "") String graphHash, Principal principal, RedirectAttributes redirect) {
        workflows.execute(id, version, graphHash, action, principal.getName());
        redirect.addFlashAttribute("success", switch (action) {
            case "run" -> "已提交整条加工流程，请进入运行记录查看结果";
            case "schedule" -> "加工流程定时调度已启用";
            default -> "加工流程定时调度已停用，已提交任务继续执行";
        });
        return "redirect:/workflows/" + id;
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable long id, @RequestParam int version, @RequestParam Map<String, String> params,
                  Principal principal, RedirectAttributes redirect) {
        var page = QueryPage.parse(params, 20);
        var target = UriComponentsBuilder.fromPath("/workflows")
            .queryParam("pageNum", page.pageNum()).queryParam("pageSize", page.pageSize());
        String keyword = params.getOrDefault("keyword", "").trim();
        if (keyword.length() > 100) throw new IllegalArgumentException("搜索关键字最长100字符");
        if (!keyword.isBlank()) target.queryParam("keyword", keyword);
        workflows.archive(id, version, principal.getName());
        redirect.addFlashAttribute("success", "加工流程及其 SQL 任务已归档；同步管道、物理数据和运行历史已保留");
        return "redirect:" + target.build().encode().toUriString();
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    Object duplicate(HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "名称或目标模型已有有效 SQL 任务，请选择其他名称或目标模型");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    Object bad(IllegalArgumentException e, HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    Object status(ResponseStatusException e, HttpServletRequest request) {
        return error(request, e.getStatusCode(), Objects.toString(e.getReason(), "请求失败"));
    }

    private Object error(HttpServletRequest request, HttpStatusCode status, String message) {
        if (wantsHtml(request))
            return new org.springframework.web.servlet.ModelAndView(status.is4xxClientError() ? "error/4xx" : "error/5xx", Map.of("status", status.value()), status);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }

    private boolean wantsHtml(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.endsWith("/graph") || path.contains("/sql-tasks") || path.endsWith("/catalog") || path.endsWith("/expand") || path.endsWith("/sql-models"))
            return false;
        return Objects.toString(request.getHeader(HttpHeaders.ACCEPT), "").contains(MediaType.TEXT_HTML_VALUE);
    }

    private static ResponseEntity<?> json(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
