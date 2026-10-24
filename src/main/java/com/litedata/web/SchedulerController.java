package com.litedata.web;

import com.litedata.scheduling.*;
import com.litedata.service.QueryPage;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Clock;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/scheduling")
public class SchedulerController {
    private final SchedulerOperations operations;
    private final DolphinMonitorClient engine;
    private static final Map<String,String> FILTERS=Map.of("SUCCESS","成功","FAILURE","失败","RUNNING_EXECUTION","运行中","STOP","已停止","PAUSE","已暂停");
    public SchedulerController(SchedulerOperations operations,DolphinMonitorClient engine) { this.operations=operations; this.engine=engine; }
    @ModelAttribute("canEdit") boolean canEdit(HttpServletRequest request) { return request.isUserInRole("ADMIN") || request.isUserInRole("DEVELOPER"); }
    @ModelAttribute("canManageUsers") boolean admin(HttpServletRequest request) { return request.isUserInRole("ADMIN"); }
    @ModelAttribute("statusLabels") Map<String,String> labels() {
        var values=new HashMap<>(FILTERS); values.putAll(Map.of("SUBMITTED_SUCCESS","已提交","SERIAL_WAIT","排队中","DELAY_EXECUTION","延迟等待",
                "READY_STOP","停止中","READY_PAUSE","暂停中","FORCED_SUCCESS","强制成功","KILL","已终止","WAITING_DEPEND","等待上游"));
        values.putAll(Map.of("FAILOVER","故障恢复中","DISPATCH","派发中","WAITING_THREAD","等待执行资源","NEED_FAULT_TOLERANCE","待故障恢复"));
        return values;
    }
    @GetMapping("/preview") @ResponseBody
    SchedulePreview.Preview preview(@RequestParam String cron) {
        try { return SchedulePreview.preview(cron,Clock.systemUTC()); }
        catch(IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,exception.getMessage()); }
    }
    @GetMapping("/{type}/{id}")
    String instances(@PathVariable String type,@PathVariable long id,@RequestParam Map<String,String> params,Model model) {
        var resource=operations.resource(type,id); prepare(model,resource);
        var page=QueryPage.parse(params,20);
        if(page.pageSize()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"运行实例每页最多100条");
        String state=params.getOrDefault("stateType","");
        if(!state.isEmpty() && !FILTERS.containsKey(state)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"实例状态无效");
        long code=resource.codes().isEmpty()?0:resource.codes().getFirst();
        if(params.containsKey("workflowCode")) {
            try { code=Long.parseLong(params.get("workflowCode")); }
            catch(NumberFormatException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"执行链路版本无效"); }
            operations.checkCode(resource,code);
        }
        model.addAttribute("workflowCode",code); model.addAttribute("stateType",state); model.addAttribute("stateFilters",FILTERS);
        model.addAttribute("instances",List.of());
        ListPagination.prepare(model,resource.path(),params,0);
        if(code!=0) try {
            var result=engine.instances(code,page,state);
            var actual=ListPagination.prepare(model,resource.path(),params,result.total());
            if(actual.pageNum()!=page.pageNum()) result=engine.instances(code,actual,state);
            for(var instance:result.items()) if(instance.definitionCode()!=code) throw new IllegalStateException("引擎返回的执行链路不匹配");
            model.addAttribute("instances",result.items());
        } catch(IllegalStateException exception) { model.addAttribute("engineError",exception.getMessage()); }
        return "scheduler-instances";
    }
    @GetMapping("/{type}/{id}/instances/{instanceId}")
    String detail(@PathVariable String type,@PathVariable long id,@PathVariable long instanceId,
                  @RequestParam(required=false) Long taskId,Model model,HttpServletRequest request) {
        var resource=operations.resource(type,id); prepare(model,resource);
        model.addAttribute("instanceId",instanceId);
        model.addAttribute("instance",null); model.addAttribute("nodes",List.of()); model.addAttribute("selectedNode",null);
        try {
            var snapshot=operations.snapshot(resource,instanceId);
            var instance=snapshot.instance(); var nodes=snapshot.nodes();
            model.addAttribute("instance",instance); model.addAttribute("nodes",nodes);
            if(canEdit(request) && !nodes.isEmpty()) {
                var selected=taskId==null?nodes.stream().filter(n->n.state().equals("FAILURE")).findFirst().orElse(nodes.getFirst())
                        :nodes.stream().filter(n->n.id()==taskId).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"节点不属于该工作流实例"));
                model.addAttribute("selectedNode",selected);
                model.addAttribute("logUrl",resource.path()+"/instances/"+instanceId+"/tasks/"+selected.id()+"/log");
            }
        } catch(IllegalStateException exception) { model.addAttribute("engineError",exception.getMessage()); }
        return "scheduler-instance-detail";
    }
    @GetMapping("/{type}/{id}/instances/{instanceId}/status") @ResponseBody
    ResponseEntity<?> status(@PathVariable String type,@PathVariable long id,@PathVariable long instanceId) {
        try {
            var snapshot=operations.snapshot(operations.resource(type,id),instanceId);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                    "instance",snapshot.instance(),"nodes",snapshot.nodes(),"finished",snapshot.finished(),
                    "updatedAt",snapshot.updatedAt(),"labels",labels()));
        } catch(IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(Map.of("message",exception.getMessage()));
        }
    }
    @GetMapping("/{type}/{id}/instances/{instanceId}/tasks/{taskId}/log") @ResponseBody
    ResponseEntity<?> log(@PathVariable String type,@PathVariable long id,@PathVariable long instanceId,@PathVariable long taskId,
                         @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="200") int limit,Principal principal) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.log(operations.resource(type,id),instanceId,taskId,offset,limit,principal.getName()));
        } catch(IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(Map.of("message",exception.getMessage()));
        }
    }
    private void prepare(Model model,SchedulerOperations.Resource resource) {
        model.addAttribute("resource",resource); model.addAttribute("activeNav",resource.type().equals("pipeline")?"pipelines":"transforms");
    }
}
