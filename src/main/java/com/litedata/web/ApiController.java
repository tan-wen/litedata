package com.litedata.web;

import com.litedata.config.PlatformProperties;
import com.litedata.pipeline.PipelineService;
import com.litedata.service.DataApiService;
import com.litedata.scheduling.DolphinMonitorClient;
import com.litedata.scheduling.SchedulerOperations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ApiController {
    private final DataApiService api; private final PipelineService pipelines; private final byte[] internalToken;
    private final DolphinMonitorClient engine; private final SchedulerOperations operations;
    public ApiController(DataApiService api,PipelineService pipelines,PlatformProperties properties,DolphinMonitorClient engine,SchedulerOperations operations) {
        this.api=api; this.pipelines=pipelines; internalToken=properties.internalToken().getBytes(StandardCharsets.UTF_8);
        this.engine=engine; this.operations=operations;
    }
    @GetMapping("/api/data/{slug}")
    Map<String,Object> query(@PathVariable String slug,@RequestHeader(value="X-API-Key",required=false) String token,@RequestParam Map<String,String> params) {
        return api.query(slug,token,params);
    }
    @PostMapping("/internal/pipelines/{id}/claim")
    ResponseEntity<Map<String,Object>> claim(@PathVariable long id,@RequestHeader(value="X-Internal-Token",required=false) String token,
                                           @RequestBody(required=false) ClaimRequest request) {
        authorize(token);
        Long workflow=request==null?null:request.workflowInstanceId(),task=request==null?null:request.taskInstanceId();
        if(workflow!=null || task!=null) {
            if(task==null || task<=0 || (workflow!=null && workflow<=0))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"节点实例编号必须为正整数");
            try {
                long actual=engine.workflowForTask(task);
                if(workflow!=null && workflow!=actual) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"节点不属于提供的工作流实例");
                operations.instance(operations.resource("pipeline",id),actual);
                workflow=actual;
            } catch(IllegalStateException exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"无法核验调度实例归属，请稍后重试"); }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pipelines.claim(id,workflow,task));
    }
    @PostMapping("/internal/runs/{id}/finish")
    Map<String,String> finish(@PathVariable long id,@RequestHeader(value="X-Internal-Token",required=false) String token,@RequestBody FinishRequest request) {
        authorize(token); pipelines.finish(id,request.success(),request.message()); return Map.of("status","accepted");
    }
    private void authorize(String token) {
        if(token==null || !MessageDigest.isEqual(internalToken,token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"执行节点凭证无效");
    }
    public record FinishRequest(boolean success,String message) {}
    public record ClaimRequest(Long workflowInstanceId,Long taskInstanceId) {}
}

