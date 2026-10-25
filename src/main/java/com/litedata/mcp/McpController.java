package com.litedata.mcp;

import com.litedata.security.UserApiKeyService.Identity;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** Bounded, stateless Streamable HTTP: JSON replies, no sessions or SSE. */
@RestController
public class McpController {
    private static final List<String> VERSIONS=List.of("2025-11-25","2025-06-18","2025-03-26");
    private final ObjectMapper json;
    private final McpToolService tools;
    public McpController(ObjectMapper json,McpToolService tools) { this.json=json; this.tools=tools; }
    @PostMapping(value="/mcp",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> rpc(HttpServletRequest request) throws IOException {
        byte[] body=request.getInputStream().readNBytes(65537);
        if(body.length>65536) return reply(413,error(null,-32600,"请求体最多64 KiB"));
        JsonNode rpc;
        try { rpc=json.readTree(body); }
        catch(RuntimeException exception) { return reply(400,error(null,-32700,"JSON 解析失败")); }
        if(rpc==null || !rpc.isObject() || !rpc.path("jsonrpc").asString("").equals("2.0")
                || !rpc.path("method").isString() || (rpc.has("params") && !rpc.get("params").isObject())
                || (rpc.has("id") && !rpc.get("id").isString() && !rpc.get("id").isIntegralNumber()))
            return reply(400,error(null,-32600,"无效的 JSON-RPC 请求"));
        Object id=rpc.has("id")?json.treeToValue(rpc.get("id"),Object.class):null;
        String method=rpc.get("method").asString();
        String version=request.getHeader("MCP-Protocol-Version");
        if(version!=null && !VERSIONS.contains(version)) return reply(400,error(id,-32600,"不支持的 MCP 协议版本"));
        // Only protocol notifications are accepted without an ID; never execute a tool notification.
        if(!rpc.has("id")) {
            if(method.equals("notifications/initialized") || method.equals("notifications/cancelled")) return reply(202,null);
            return reply(400,error(null,-32600,"此方法需要请求 ID"));
        }
        var identity=(Identity)request.getAttribute(McpSecurityFilter.IDENTITY);
        if(identity==null) return reply(401,error(id,-32001,"需要 MCP 凭证"));
        var params=rpc.has("params")?rpc.get("params"):json.createObjectNode();
        try {
            Object result=switch(method) {
                case "initialize" -> {
                    if(!params.path("protocolVersion").isString() || !params.path("capabilities").isObject()
                            || !params.path("clientInfo").isObject()) throw new IllegalArgumentException("initialize 参数无效");
                    String requested=params.get("protocolVersion").asString();
                    yield Map.of("protocolVersion",VERSIONS.contains(requested)?requested:VERSIONS.getFirst(),
                            "capabilities",Map.of("tools",Map.of("listChanged",false)),
                            "serverInfo",Map.of("name","litedata","version","0.1.0"));
                }
                case "ping" -> Map.of();
                case "tools/list" -> {
                    if(params.has("cursor") && !params.get("cursor").isNull()) throw new IllegalArgumentException("工具列表不支持此 cursor");
                    yield Map.of("tools",tools.list(identity));
                }
                case "tools/call" -> {
                    if(!params.path("name").isString()) throw new IllegalArgumentException("缺少工具名称");
                    var args=params.has("arguments")?params.get("arguments"):json.createObjectNode();
                    var output=tools.call(identity,params.get("name").asString(),args);
                    yield Map.of("content",List.of(Map.of("type","text","text",json.writeValueAsString(output))),"structuredContent",output,"isError",false);
                }
                default -> null;
            };
            return result==null?reply(200,error(id,-32601,"方法不存在")):reply(200,Map.of("jsonrpc","2.0","id",id,"result",result));
        } catch(McpToolService.UnknownToolException exception) {
            return reply(200,error(id,-32602,"工具不存在"));
        } catch(ResponseStatusException exception) {
            int status=exception.getStatusCode().value();
            if(status==401 || status==403) return reply(status,error(id,status==401?-32001:-32003,status==401?"MCP 凭证已失效":"当前用户或 Key 无权执行此操作"));
            return toolFailure(id,status==400?"请求参数与服务定义不一致":status==429?"请求过于频繁，请稍后重试":status==404?"服务尚未发布":"工具执行失败，请检查服务状态");
        } catch(IllegalArgumentException exception) {
            return reply(200,error(id,-32602,"工具或协议参数无效"));
        } catch(RuntimeException exception) {
            return toolFailure(id,"工具执行失败，请检查服务状态");
        }
    }
    @RequestMapping(value="/mcp",method={RequestMethod.GET,RequestMethod.DELETE,RequestMethod.PUT,RequestMethod.PATCH})
    ResponseEntity<?> unsupported() { return ResponseEntity.status(405).header(HttpHeaders.ALLOW,"POST").cacheControl(CacheControl.noStore()).build(); }
    private ResponseEntity<?> toolFailure(Object id,String message) {
        return reply(200,Map.of("jsonrpc","2.0","id",id,"result",Map.of("isError",true,"content",List.of(Map.of("type","text","text",message)))));
    }
    private Map<String,Object> error(Object id,int code,String message) {
        var result=new LinkedHashMap<String,Object>(); result.put("jsonrpc","2.0"); result.put("id",id);
        result.put("error",Map.of("code",code,"message",message)); return result;
    }
    private ResponseEntity<?> reply(int status,Object body) {
        var response=ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if(status==401) response.header(HttpHeaders.WWW_AUTHENTICATE,"Bearer realm=\"litedata-mcp\"");
        return response.body(body);
    }
}
