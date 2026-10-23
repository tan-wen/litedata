package com.litedata.web;

import com.litedata.config.PlatformProperties;
import com.litedata.data.SourceService;
import com.litedata.pipeline.*;
import com.litedata.service.DataApiService;
import com.litedata.store.PlatformMapper;
import java.security.Principal;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PageController {
    private final PlatformMapper mapper; private final SourceService sources; private final PipelineService pipelines;
    private final DolphinSchedulerClient scheduler; private final DataApiService services; private final PlatformProperties properties;
    public PageController(PlatformMapper mapper,SourceService sources,PipelineService pipelines,DolphinSchedulerClient scheduler,DataApiService services,PlatformProperties properties) {
        this.mapper=mapper; this.sources=sources; this.pipelines=pipelines; this.scheduler=scheduler; this.services=services; this.properties=properties;
    }
    @GetMapping("/login") String login() { return "login"; }
    @ModelAttribute("canEdit") boolean canEdit(jakarta.servlet.http.HttpServletRequest request) {
        return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER");
    }
    @ModelAttribute("canManageUsers") boolean canManageUsers(jakarta.servlet.http.HttpServletRequest request) {
        return request.isUserInRole("ADMIN");
    }
    @ModelAttribute("activeNav") String activeNav(jakarta.servlet.http.HttpServletRequest request) {
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
    @GetMapping("/sources") String sources(Model model) { model.addAttribute("sources",sources.list()); return "sources"; }
    @PostMapping("/sources") String createSource(@RequestParam String name,@RequestParam String jdbcUrl,@RequestParam String username,
            @RequestParam(defaultValue="") String password,RedirectAttributes redirect,Principal principal) {
        sources.create(name,jdbcUrl,username,password); audit(principal,"SOURCE_CREATE",name);
        redirect.addFlashAttribute("success","数据源已保存，请测试连接"); return "redirect:/sources";
    }
    @PostMapping("/sources/{id}/test") String test(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        sources.test(id); audit(principal,"SOURCE_TEST",Long.toString(id));
        redirect.addFlashAttribute("success","连接测试成功"); return "redirect:/sources";
    }
    @GetMapping("/pipelines") String pipelines(Model model) {
        model.addAttribute("pipelines",mapper.pipelines()); model.addAttribute("sources",sources.list()); model.addAttribute("runs",displayRuns());
        return "pipelines";
    }
    @PostMapping("/pipelines") String createPipeline(@RequestParam String name,@RequestParam long sourceId,@RequestParam String sourceTable,
            @RequestParam String watermarkColumn,@RequestParam String targetTable,@RequestParam(defaultValue="") String transformSql,
            @RequestParam(defaultValue="0 0 * * * ?") String cronExpression,RedirectAttributes redirect,Principal principal) {
        pipelines.create(name,sourceId,sourceTable,watermarkColumn,targetTable,transformSql,cronExpression);
        audit(principal,"PIPELINE_CREATE",name); redirect.addFlashAttribute("success","任务已保存；确认目标表为 UNIQUE KEY 并完成建表后可执行");
        return "redirect:/pipelines";
    }
    @PostMapping("/pipelines/{id}/run") String run(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        scheduler.run(id); audit(principal,"PIPELINE_SUBMIT",Long.toString(id));
        redirect.addFlashAttribute("success","DolphinScheduler 已接受执行请求，刷新查看运行实例"); return "redirect:/pipelines";
    }
    @PostMapping("/pipelines/{id}/schedule") String schedule(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        scheduler.schedule(id); audit(principal,"PIPELINE_SCHEDULE",Long.toString(id));
        redirect.addFlashAttribute("success","每小时调度已启用"); return "redirect:/pipelines";
    }
    @GetMapping("/services") String services(Model model) { model.addAttribute("services",mapper.services()); return "services"; }
    @PostMapping("/services") String createService(@RequestParam String name,@RequestParam String slug,@RequestParam String querySql,
            @RequestParam int maxRows,RedirectAttributes redirect,Principal principal) {
        services.create(name,slug,querySql,maxRows); audit(principal,"SERVICE_CREATE",slug);
        redirect.addFlashAttribute("success","服务草稿已保存"); return "redirect:/services";
    }
    @PostMapping("/services/{id}/publish") String publish(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        services.publish(id); audit(principal,"SERVICE_PUBLISH_TOGGLE",Long.toString(id));
        redirect.addFlashAttribute("success","发布状态已更新"); return "redirect:/services";
    }
    @PostMapping("/services/{id}/credentials") String credential(@PathVariable long id,RedirectAttributes redirect,Principal principal) {
        redirect.addFlashAttribute("apiKey",services.credential(id)); audit(principal,"CREDENTIAL_ROTATE",Long.toString(id));
        redirect.addFlashAttribute("success","新凭证仅显示本次，旧凭证已吊销"); return "redirect:/services";
    }
    private void audit(Principal principal,String action,String target) { mapper.audit(principal.getName(),action,target,"SUCCESS"); }
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

