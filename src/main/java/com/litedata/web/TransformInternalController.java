package com.litedata.web;

import com.litedata.config.PlatformProperties;
import com.litedata.transform.TransformService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/sql-tasks")
public class TransformInternalController {
    private final TransformService tasks;
    private final byte[] token;
    public TransformInternalController(TransformService tasks,PlatformProperties properties) {
        this.tasks=tasks; this.token=properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }
    @PostMapping("/{id}/claim")
    ResponseEntity<Map<String,Object>> claim(@PathVariable long id,@RequestHeader(value="X-Internal-Token",required=false) String credential,
            @RequestBody Claim request) {
        authorize(credential);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.claim(id,request.version(),request.rootId(),request.graphHash()));
    }
    @PostMapping("/runs/{runId}/finish")
    Map<String,String> finish(@PathVariable long runId,@RequestHeader(value="X-Internal-Token",required=false) String credential,@RequestBody Finish request) {
        authorize(credential); tasks.finish(runId,request.success()); return Map.of("status","accepted");
    }
    private void authorize(String credential) {
        if(credential==null || !MessageDigest.isEqual(token,credential.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"执行节点凭证无效");
    }
    public record Claim(int version,long rootId,String graphHash) {}
    public record Finish(boolean success) {}
}
