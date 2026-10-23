package com.litedata.web;

import com.litedata.config.PlatformProperties;
import com.litedata.data.SourceService;
import com.litedata.pipeline.*;
import com.litedata.service.DataApiService;
import com.litedata.service.QueryPage;
import com.litedata.store.PlatformMapper;
import com.litedata.web.ResourceForms.*;
import java.security.Principal;
import java.util.Map;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PageController {
    private final PlatformMapper mapper; private final SourceService sources; private final PipelineService pipelines;
    private final DolphinSchedulerClient scheduler; private final DataApiService services; private final PlatformProperties properties;
    public PageController(PlatformMapper mapper,SourceService sources,PipelineService pipelines,DolphinSchedulerClient scheduler,DataApiService services,PlatformProperties properties) {
        this.mapper=mapper; this.sources=sources; this.pipelines=pipelines; this.scheduler=scheduler; this.services=services; this.properties=properties;
    }
    @GetMapping("/login") String login() { return "login"; }
    @InitBinder("draft") void bind(WebDataBinder binder) {
        binder.setAllowedFields("name","jdbcUrl","username","password","sourceId","sourceTable","watermarkColumn",
                "targetTable","transformSql","cronExpression","slug","querySql");
    }
    @ModelAttribute("canEdit") boolean canEdit(jakarta.servlet.http.HttpServletRequest request) {
        return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER");
    }
    @ModelAttribute("canManageUsers") boolean canManageUsers(jakarta.servlet.http.HttpServletRequest request) {
        return request.isUserInRole("ADMIN");
    }
    @ModelAttribute("activeNav") String activeNav(jakarta.servlet.http.HttpServletRequest request) {
        for(String resource:java.util.List.of("sources","pipelines","services"))
            if(request.getRequestURI().startsWith("/"+resource+"/")) return resource;
        return switch(request.getRequestURI()) {
            case "/sources" -> "sources";
            case "/pipelines" -> "pipelines";
            case "/services" -> "services";
            default -> "dashboard";
        };
    }
    @GetMapping("/") String dashboard(Model model) {
        model.addAttribute("stats",mapper.stats()); model.addAttribute("recentRuns",displayRuns());
        model.addAttribute("engineStatus",Map.of("平台数据库","已连接","DolphinScheduler",scheduler.configured()?"已配置，运行时校验":"等待初始化",
            "Doris","固定目标："+properties.doris().jdbcUrl().split("\\?")[0]));
        return "dashboard";
    }
    @GetMapping("/sources") String sources(@RequestParam Map<String,String> params,Model model) {
        var page=ListPagination.prepare(model,"/sources",params,mapper.sourceCount());
        model.addAttribute("sources",mapper.sourcePage(page.pageSize(),page.offset())); return "sources";
    }
    @GetMapping("/sources/new") String newSource(Model model) { return form(model,new SourceDraft(),null,"sources"); }
    @GetMapping("/sources/{id}/edit") String editSource(@PathVariable long id,Model model) {
        var draft=new SourceDraft(); BeanUtils.copyProperties(source(id),draft);
        return form(model,draft,id,"sources");
    }
    @PostMapping("/sources") String createSource(@ModelAttribute("draft") SourceDraft draft,BindingResult errors,
            Model model,RedirectAttributes redirect,Principal principal) {
        return saveSource(null,draft,errors,model,redirect,principal);
    }
    @PostMapping("/sources/{id}") String updateSource(@PathVariable long id,@ModelAttribute("draft") SourceDraft draft,
            BindingResult errors,Model model,RedirectAttributes redirect,Principal principal) {
        source(id); return saveSource(id,draft,errors,model,redirect,principal);
    }
    private String saveSource(Long id,SourceDraft draft,BindingResult errors,Model model,RedirectAttributes redirect,Principal principal) {
        try {
            if(errors.hasErrors()) throw new IllegalArgumentException("数据源表单格式有误");
            if(id==null) sources.create(draft.getName(),draft.getJdbcUrl(),draft.getUsername(),draft.getPassword());
            else sources.update(id,draft.getName(),draft.getJdbcUrl(),draft.getUsername(),draft.getPassword());
            audit(principal,id==null?"SOURCE_CREATE":"SOURCE_UPDATE",draft.getName());
            redirect.addFlashAttribute("success","数据源已保存，请测试连接"); return "redirect:/sources";
        } catch(IllegalArgumentException exception) { model.addAttribute("error",exception.getMessage()); }
        catch(DuplicateKeyException exception) { model.addAttribute("error","数据源名称已存在，请使用其他名称"); }
        failedSave(principal,"sources",id);
        draft.setPassword("");
        return form(model,draft,id,"sources");
    }
    @PostMapping("/sources/{id}/test") String test(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        sources.test(id); audit(principal,"SOURCE_TEST",Long.toString(id));
        redirect.addFlashAttribute("success","连接测试成功"); return "redirect:/sources";
    }
    @GetMapping("/pipelines") String pipelines(@RequestParam Map<String,String> params,Model model) {
        var page=ListPagination.prepare(model,"/pipelines",params,mapper.pipelineCount());
        model.addAttribute("pipelines",mapper.pipelinePage(page.pageSize(),page.offset())); model.addAttribute("runs",displayRuns());
        return "pipelines";
    }
    @GetMapping("/pipelines/new") String newPipeline(Model model) { return form(model,new PipelineDraft(),null,"pipelines"); }
    @GetMapping("/pipelines/{id}/edit") String editPipeline(@PathVariable long id,Model model) {
        var draft=new PipelineDraft(); BeanUtils.copyProperties(pipeline(id),draft);
        return form(model,draft,id,"pipelines");
    }
    @PostMapping("/pipelines") String createPipeline(@ModelAttribute("draft") PipelineDraft draft,BindingResult errors,
            Model model,RedirectAttributes redirect,Principal principal) {
        return savePipeline(null,draft,errors,model,redirect,principal);
    }
    @PostMapping("/pipelines/{id}") String updatePipeline(@PathVariable long id,@ModelAttribute("draft") PipelineDraft draft,
            BindingResult errors,Model model,RedirectAttributes redirect,Principal principal) {
        pipeline(id); return savePipeline(id,draft,errors,model,redirect,principal);
    }
    private String savePipeline(Long id,PipelineDraft draft,BindingResult errors,Model model,RedirectAttributes redirect,Principal principal) {
        try {
            if(errors.hasErrors() || draft.getSourceId()==null) throw new IllegalArgumentException("请选择有效的数据源并检查表单格式");
            if(id==null) pipelines.create(draft.getName(),draft.getSourceId(),draft.getSourceTable(),draft.getWatermarkColumn(),
                    draft.getTargetTable(),draft.getTransformSql(),draft.getCronExpression());
            else pipelines.update(id,draft.getName(),draft.getSourceId(),draft.getSourceTable(),draft.getWatermarkColumn(),
                    draft.getTargetTable(),draft.getTransformSql(),draft.getCronExpression());
            audit(principal,id==null?"PIPELINE_CREATE":"PIPELINE_UPDATE",draft.getName());
            redirect.addFlashAttribute("success","任务已保存；确认目标表为 UNIQUE KEY 并完成建表后可执行");
            return "redirect:/pipelines";
        } catch(IllegalArgumentException exception) { model.addAttribute("error",exception.getMessage()); }
        catch(DuplicateKeyException exception) { model.addAttribute("error","管道名称已存在，请使用其他名称"); }
        failedSave(principal,"pipelines",id);
        return form(model,draft,id,"pipelines");
    }
    @PostMapping("/pipelines/{id}/run") String run(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        scheduler.run(id); audit(principal,"PIPELINE_SUBMIT",Long.toString(id));
        redirect.addFlashAttribute("success","DolphinScheduler 已接受执行请求，刷新查看运行实例"); return "redirect:/pipelines";
    }
    @PostMapping("/pipelines/{id}/schedule") String schedule(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        scheduler.schedule(id); audit(principal,"PIPELINE_SCHEDULE",Long.toString(id));
        redirect.addFlashAttribute("success","每小时调度已启用"); return "redirect:/pipelines";
    }
    @GetMapping("/services") String services(@RequestParam Map<String,String> params,Model model) {
        var page=ListPagination.prepare(model,"/services",params,mapper.serviceCount());
        model.addAttribute("services",services.list(page)); return "services";
    }
    @GetMapping("/services/new") String newService(Model model) { return form(model,new ServiceDraft(),null,"services"); }
    @GetMapping("/services/{id}/edit") String editService(@PathVariable long id,Model model) {
        var draft=new ServiceDraft(); BeanUtils.copyProperties(services.get(id),draft);
        return form(model,draft,id,"services");
    }
    @GetMapping("/services/{id}/calls") String serviceCalls(@PathVariable long id,@RequestParam Map<String,String> params,Model model) {
        var service=services.get(id);
        var page=QueryPage.parse(params,20);
        var calls=services.calls(id,page);
        boolean hasMore=calls.size()>page.pageSize();
        model.addAttribute("service",service);
        model.addAttribute("calls",hasMore?calls.subList(0,page.pageSize()):calls);
        model.addAttribute("pageNum",page.pageNum()); model.addAttribute("pageSize",page.pageSize());
        model.addAttribute("hasMore",hasMore);
        return "service-calls";
    }
    @PostMapping("/services") String createService(@ModelAttribute("draft") ServiceDraft draft,BindingResult errors,
            Model model,RedirectAttributes redirect,Principal principal) {
        return saveService(null,draft,errors,model,redirect,principal);
    }
    @PostMapping("/services/{id}") String updateService(@PathVariable long id,@ModelAttribute("draft") ServiceDraft draft,
            BindingResult errors,Model model,RedirectAttributes redirect,Principal principal) {
        services.get(id); return saveService(id,draft,errors,model,redirect,principal);
    }
    private String saveService(Long id,ServiceDraft draft,BindingResult errors,Model model,RedirectAttributes redirect,Principal principal) {
        try {
            if(errors.hasErrors()) throw new IllegalArgumentException("服务表单格式有误");
            if(id==null) services.create(draft.getName(),draft.getSlug(),draft.getQuerySql());
            else services.update(id,draft.getName(),draft.getSlug(),draft.getQuerySql());
            audit(principal,id==null?"SERVICE_CREATE":"SERVICE_UPDATE",draft.getSlug());
            redirect.addFlashAttribute("success","服务草稿已保存，请确认后发布"); return "redirect:/services";
        } catch(IllegalArgumentException exception) { model.addAttribute("error",exception.getMessage()); }
        catch(DuplicateKeyException exception) { model.addAttribute("error","服务标识已存在，请使用其他标识"); }
        failedSave(principal,"services",id);
        return form(model,draft,id,"services");
    }
    @PostMapping("/services/{id}/publish") String publish(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        services.publish(id); audit(principal,"SERVICE_PUBLISH_TOGGLE",Long.toString(id));
        redirect.addFlashAttribute("success","发布状态已更新"); return "redirect:/services";
    }
    @PostMapping("/services/{id}/credentials") String credential(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        redirect.addFlashAttribute("apiKey",services.credential(id)); audit(principal,"CREDENTIAL_ROTATE",Long.toString(id));
        redirect.addFlashAttribute("success","新凭证仅显示本次，旧凭证已吊销"); return "redirect:/services";
    }
    private String form(Model model,Object draft,Long id,String resource) {
        model.addAttribute("draft",draft); model.addAttribute("resourceId",id);
        model.addAttribute("formAction","/"+resource+(id==null?"":"/"+id));
        if("pipelines".equals(resource)) model.addAttribute("sources",sources.list());
        return switch(resource) {
            case "sources" -> "source-form";
            case "pipelines" -> "pipeline-form";
            default -> "service-form";
        };
    }
    private com.litedata.model.DataSource source(long id) {
        var source=mapper.source(id);
        if(source==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"数据源不存在");
        return source;
    }
    private com.litedata.model.Pipeline pipeline(long id) {
        var pipeline=mapper.pipeline(id);
        if(pipeline==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"同步管道不存在");
        return pipeline;
    }
    private void audit(Principal principal,String action,String target) { mapper.audit(principal.getName(),action,target,"SUCCESS"); }
    private void failedSave(Principal principal,String resource,Long id) {
        mapper.audit(principal.getName(),"PAGE_MUTATION","/"+resource+(id==null?"":"/"+id),"FAILED");
    }
    private java.util.List<com.litedata.model.PipelineRun> displayRuns() {
        var runs=mapper.recentRuns();
        var zone=java.time.ZoneId.of("Asia/Shanghai");
        for(var run:runs) {
            if(run.getStartedAt()!=null) run.setStartedAt(run.getStartedAt().atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime());
            if(run.getFinishedAt()!=null) run.setFinishedAt(run.getFinishedAt().atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime());
        }
        return runs;
    }
}

