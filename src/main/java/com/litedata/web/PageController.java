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
    private final com.litedata.store.ModelMapper models;
    private final PipelineTransformMigration migration;
    private final OdsPipelineService odsPipelines;
    private final com.litedata.data.SourceSchemaInspector sourceSchemas;
    public PageController(PlatformMapper mapper,SourceService sources,PipelineService pipelines,DolphinSchedulerClient scheduler,DataApiService services,PlatformProperties properties,
            com.litedata.store.ModelMapper models,PipelineTransformMigration migration,OdsPipelineService odsPipelines,
            com.litedata.data.SourceSchemaInspector sourceSchemas) {
        this.mapper=mapper; this.sources=sources; this.pipelines=pipelines; this.scheduler=scheduler; this.services=services; this.properties=properties;
        this.models=models; this.migration=migration;
        this.odsPipelines=odsPipelines; this.sourceSchemas=sourceSchemas;
    }
    @GetMapping("/login") String login() { return "login"; }
    @ModelAttribute("workflowId") Long workflowContext(@RequestParam(required=false) Long workflowId) {
        if(workflowId!=null && workflowId<=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"工作流编号无效");
        return workflowId;
    }
    @InitBinder("draft") void bind(WebDataBinder binder) {
        binder.setAllowedFields("name","databaseType","jdbcUrl","username","password","sourceId","sourceTable","watermarkColumn",
                "targetTable","transformSql","cronExpression","slug","querySql","targetMode","keyColumns","previewHash","confirmed","autoBuckets","buckets","replicationNum",
                "wizardStep","distributionType","distributionColumns","partitionType","partitionColumn","partitionUnit","partitionRangeStyle","dynamicPartitionProperties",
                "partitions[*].name","partitions[*].lowerBound","partitions[*].upperBound","partitions[*].valuesText");
        binder.setAutoGrowCollectionLimit(100);
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
        draft.setDatabaseType(source(id).getDatabaseType().name());
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
            if(draft.getDatabaseType()!=null && !draft.getDatabaseType().isBlank() &&
                    !com.litedata.data.DatabaseType.fromUrl(draft.getJdbcUrl()).name().equals(draft.getDatabaseType()))
                throw new IllegalArgumentException("所选数据库类型与 JDBC 地址不一致");
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
        model.addAttribute("pipelines",mapper.pipelinePage(page.pageSize(),page.offset()));
        return "pipelines";
    }
    @GetMapping("/pipelines/{id}/runs") String pipelineRuns(@PathVariable long id,@RequestParam Map<String,String> params,Model model) {
        model.addAttribute("pipeline",pipeline(id));
        var page=ListPagination.prepare(model,"/pipelines/"+id+"/runs",params,mapper.pipelineRunCount(id));
        model.addAttribute("runs",displayRuns(mapper.pipelineRunPage(id,page.pageSize(),page.offset())));
        return "pipeline-runs";
    }
    @GetMapping("/pipelines/new") String newPipeline(Model model) {
        var draft=new PipelineDraft(); draft.setTargetMode("AUTO");
        return form(model,draft,null,"pipelines");
    }
    @GetMapping("/pipelines/source-tables") @ResponseBody org.springframework.http.ResponseEntity<?> sourceTables(@RequestParam long sourceId) {
        try { return org.springframework.http.ResponseEntity.ok(sourceSchemas.tables(sourceId)); }
        catch(IllegalArgumentException exception) { return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error",exception.getMessage())); }
    }
    @PostMapping("/pipelines/preview") String previewPipeline(@ModelAttribute("draft") PipelineDraft draft,BindingResult errors,Model model) {
        draft.setConfirmed(false); draft.setPreviewHash("");
        if("EXISTING".equals(draft.getTargetMode())) {
            draft.setTargetTable(""); draft.setKeyColumns(java.util.List.of());
            draft.setWizardStep(1);
            return form(model,draft,null,"pipelines");
        }
        try {
            if(errors.hasErrors() || draft.getSourceId()==null || !"AUTO".equals(draft.getTargetMode()))
                throw new IllegalArgumentException("请选择数据源、来源表及自动 ODS 接入方式");
            var preview=odsPipelines.preview(odsInput(draft));
            draft.setTargetTable(preview.model().getTableName()); draft.setWatermarkColumn(preview.watermarkColumn());
            draft.setKeyColumns(preview.fields().stream().filter(OdsPipelineService.Mapping::keyColumn).map(OdsPipelineService.Mapping::sourceName).toList());
            draft.setPreviewHash(preview.previewHash()); model.addAttribute("odsPreview",preview);
            draft.setWizardStep(Math.max(2,draft.getWizardStep()));
        } catch(IllegalArgumentException exception) {
            model.addAttribute("error",exception.getMessage());
            if(exception instanceof OdsPipelineService.PreviewFailure failure) {
                var fields=failure.fields(); model.addAttribute("odsPreview",fields);
                draft.setTargetTable(fields.model().getTableName()); draft.setWatermarkColumn(fields.watermarkColumn());
                draft.setKeyColumns(fields.fields().stream().filter(OdsPipelineService.Mapping::keyColumn).map(OdsPipelineService.Mapping::sourceName).toList());
                draft.setWizardStep(Math.max(2,draft.getWizardStep()));
            }
        }
        return form(model,draft,null,"pipelines");
    }
    @GetMapping("/pipelines/{id}/edit") String editPipeline(@PathVariable long id,Model model) {
        var draft=new PipelineDraft(); BeanUtils.copyProperties(pipeline(id),draft);
        draft.setTransformSql(""); draft.setTargetMode("EXISTING");
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
            if(!java.util.Set.of("AUTO","EXISTING").contains(draft.getTargetMode())) throw new IllegalArgumentException("请选择有效的 ODS 接入方式");
            if(id==null && "AUTO".equals(draft.getTargetMode())) {
                if(draft.getTransformSql()!=null && !draft.getTransformSql().isBlank()) throw new IllegalArgumentException("加工 SQL 请在数据加工中配置");
                odsPipelines.create(odsInput(draft),draft.getPreviewHash(),draft.isConfirmed(),principal.getName());
            } else if(id==null) pipelines.create(draft.getName(),draft.getSourceId(),draft.getSourceTable(),draft.getWatermarkColumn(),
                    draft.getTargetTable(),draft.getTransformSql(),draft.getCronExpression());
            else pipelines.update(id,draft.getName(),draft.getSourceId(),draft.getSourceTable(),draft.getWatermarkColumn(),
                    draft.getTargetTable(),draft.getTransformSql(),draft.getCronExpression());
            audit(principal,id==null?"PIPELINE_CREATE":"PIPELINE_UPDATE",draft.getName());
            redirect.addFlashAttribute("success",id==null && "AUTO".equals(draft.getTargetMode())
                    ?"ODS 表、模型与同步管道已创建，可以运行同步；加工请在数据加工中配置"
                    :"ODS 同步管道已保存；完成目标模型建表后可执行，加工请在数据加工中配置");
            if(model.getAttribute("workflowId") instanceof Long workflowId) return "redirect:/workflows/"+workflowId;
            return "redirect:/pipelines";
        } catch(IllegalArgumentException exception) { model.addAttribute("error",exception.getMessage()); }
        catch(DuplicateKeyException exception) { model.addAttribute("error","管道名称或目标模型已存在，请核对后选择已有 ODS 或调整名称"); }
        failedSave(principal,"pipelines",id);
        draft.setConfirmed(false); draft.setPreviewHash("");
        return form(model,draft,id,"pipelines");
    }
    private static OdsPipelineService.Input odsInput(PipelineDraft draft) {
        return new OdsPipelineService.Input(draft.getName(),draft.getSourceId(),draft.getSourceTable(),draft.getWatermarkColumn(),
                draft.getTargetTable(),draft.getCronExpression(),draft.getKeyColumns(),draft.isAutoBuckets(),draft.getBuckets(),draft.getReplicationNum(),
                new OdsPipelineService.PhysicalSettings(draft.getDistributionType(),draft.getDistributionColumns(),draft.getPartitionType(),
                        draft.getPartitionColumn(),draft.getPartitionUnit(),draft.getPartitionRangeStyle(),draft.getDynamicPartitionProperties(),draft.getPartitions()));
    }
    @PostMapping("/pipelines/{id}/run") String run(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        scheduler.run(id); audit(principal,"PIPELINE_SUBMIT",Long.toString(id));
        redirect.addFlashAttribute("success","DolphinScheduler 已接受执行请求，刷新查看运行实例"); return "redirect:/pipelines";
    }
    @PostMapping("/pipelines/{id}/schedule") String schedule(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        scheduler.schedule(id); audit(principal,"PIPELINE_SCHEDULE",Long.toString(id));
        redirect.addFlashAttribute("success","定时调度已启用"); return "redirect:/pipelines";
    }
    @PostMapping("/pipelines/{id}/pause") String pause(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        scheduler.pause(id); audit(principal,"PIPELINE_PAUSE",Long.toString(id));
        redirect.addFlashAttribute("success","定时调度已停用，已提交的实例继续执行"); return "redirect:/pipelines";
    }
    @PostMapping("/pipelines/{id}/migrate-transform") String migrateTransform(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        long taskId=migration.migrate(id,principal.getName());
        redirect.addFlashAttribute("success","旧加工 SQL 已迁移并重接依赖；历史记录与 SQL 已保留，请核对 SQL、依赖和调度周期后运行或启用末端调度");
        return "redirect:/transforms/"+taskId;
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
        if("pipelines".equals(resource)) {
            if(!model.containsAttribute("odsPreview")) model.addAttribute("odsPreview",null);
            model.addAttribute("sources",sources.list());
            model.addAttribute("backendStatus",odsPipelines.backendStatus());
            model.addAttribute("odsModels",models.models().stream().filter(m->"ODS".equals(m.getLayer()) && "UNIQUE".equals(m.getModelType())).toList());
            model.addAttribute("legacyTransformSql",id==null?"":pipeline(id).getTransformSql());
            model.addAttribute("migratedTaskId",id==null?null:migration.migratedTaskId(id));
        }
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
        return displayRuns(mapper.recentRuns());
    }
    private java.util.List<com.litedata.model.PipelineRun> displayRuns(java.util.List<com.litedata.model.PipelineRun> runs) {
        var zone=java.time.ZoneId.of("Asia/Shanghai");
        for(var run:runs) {
            if(run.getStartedAt()!=null) run.setStartedAt(run.getStartedAt().atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime());
            if(run.getFinishedAt()!=null) run.setFinishedAt(run.getFinishedAt().atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime());
        }
        return runs;
    }
}

