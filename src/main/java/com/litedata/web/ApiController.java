package com.litedata.web;

import com.litedata.config.PlatformProperties;
import com.litedata.pipeline.PipelineService;
import com.litedata.service.DataApiService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ApiController {
    private final DataApiService api; private final PipelineService pipelines; private final byte[] internalToken;
    public ApiController(DataApiService api,PipelineService pipelines,PlatformProperties properties) {
        this.api=api; this.pipelines=pipelines; internalToken=properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/api/data/{slug}")
    Map<String,Object> query(@PathVariable String slug,@RequestHeader(value="X-API-Key",required=false) String token,@RequestParam Map<String,String> params) {
        return api.query(slug,token,params);
    }
    @PostMapping("/internal/pipelines/{id}/claim")
    ResponseEntity<Map<String,Object>> claim(@PathVariable long id,@RequestHeader(value="X-Internal-Token",required=false) String token) {
        authorize(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pipelines.claim(id));
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
}

