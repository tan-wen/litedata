package com.litedata.mcp;

import com.litedata.pipeline.DolphinSchedulerClient;
import com.litedata.security.UserApiKeyService;
import com.litedata.security.UserApiKeyService.Identity;
import com.litedata.service.*;
import com.litedata.store.PlatformMapper;
import com.litedata.store.UserApiKeyMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class McpToolService {
    private final UserApiKeyService keys;
    private final UserApiKeyMapper grants;
    private final PlatformMapper platform;
    private final DataApiService data;
    private final QueryPolicy policy;
    private final DolphinSchedulerClient scheduler;
    public McpToolService(UserApiKeyService keys,UserApiKeyMapper grants,PlatformMapper platform,DataApiService data,
                          QueryPolicy policy,DolphinSchedulerClient scheduler) {
        this.keys=keys; this.grants=grants; this.platform=platform; this.data=data; this.policy=policy; this.scheduler=scheduler;
    }
    public List<Map<String,Object>> list(Identity identity) {
        var current=keys.refresh(identity);
        var result=new ArrayList<Map<String,Object>>();
        result.add(tool("get_current_user","查看当前用户及有效权限",Map.of(),List.of(),true));
        if(keys.hasScope(current,UserApiKeyService.READ))
            result.add(tool("list_data_services","分页发现白名单内已发布的数据服务及其参数",Map.of(
                    "pageNum",Map.of("type","integer","minimum",1),
                    "pageSize",Map.of("type","integer","minimum",1,"maximum",100)),List.of(),true));
        if(keys.hasScope(current,UserApiKeyService.QUERY))
            result.add(tool("query_data_service","执行已授权的只读数据服务；params 中的业务参数必须与服务定义一致",Map.of(
                    "slug",Map.of("type","string"),"params",Map.of("type","object","additionalProperties",Map.of("type","string"))),List.of("slug"),true));
        if(keys.hasScope(current,UserApiKeyService.RUN))
            result.add(tool("run_pipeline","提交已授权管道执行；会产生写入，提交成功不代表任务完成，请先获得用户执行指令",Map.of(
                    "pipelineId",Map.of("type","integer","minimum",1)),List.of("pipelineId"),false));
        return result;
    }
    public Map<String,Object> call(Identity identity,String name,JsonNode arguments) {
        String target="key:"+identity.keyId()+"/tool:"+(Set.of("get_current_user","list_data_services","query_data_service","run_pipeline").contains(name)?name:"unknown");
        String outcome="FAILED";
        try {
            var current=keys.refresh(identity);
            Map<String,Object> result;
            switch(name) {
                case "get_current_user" -> {
                    fields(arguments,Set.of());
                    var scopes=List.of(UserApiKeyService.READ,UserApiKeyService.QUERY,UserApiKeyService.RUN).stream().filter(s->keys.hasScope(current,s)).toList();
                    result=Map.of("userId",current.userId(),"username",current.username(),"role",current.role(),"keyId",current.keyId(),"scopes",scopes);
                }
                case "list_data_services" -> {
                    fields(arguments,Set.of("pageNum","pageSize"));
                    keys.requireScope(current,UserApiKeyService.READ);
                    var page=new QueryPage(integer(arguments,"pageNum",1),integer(arguments,"pageSize",20));
                    if(page.pageSize()>100) throw new IllegalArgumentException("pageSize 最多100");
                    var services=grants.services(current.keyId(),page.pageSize()+1,page.offset());
                    boolean more=services.size()>page.pageSize();
                    var rows=services.stream().limit(page.pageSize()).map(s->Map.<String,Object>of("id",s.getId(),"name",s.getName(),
                            "slug",s.getSlug(),"parameters",new LinkedHashSet<>(policy.validate(s.getQuerySql()).parameters()))).toList();
                    result=Map.of("services",rows,"pageNum",page.pageNum(),"pageSize",page.pageSize(),"hasMore",more);
                }
                case "query_data_service" -> {
                    fields(arguments,Set.of("slug","params"));
                    keys.requireScope(current,UserApiKeyService.QUERY);
                    var slug=arguments.get("slug");
                    if(slug==null || !slug.isString() || !slug.asString().matches("[a-z][a-z0-9-]{0,79}"))
                        throw new IllegalArgumentException("请提供有效的服务标识");
                    var service=platform.serviceBySlug(slug.asString());
                    target+="/service:"+(service==null?"unknown":service.getId());
                    // Check grants before publication or existence errors can reveal another resource.
                    keys.requireService(current,service==null?-1:service.getId(),UserApiKeyService.QUERY);
                    var params=new HashMap<String,String>();
                    JsonNode values=arguments.get("params");
                    if(values!=null) {
                        if(!values.isObject() || values.size()>100) throw new IllegalArgumentException("params 须为字符串键值对象");
                        for(var entry:values.properties()) {
                            if(!entry.getValue().isString()) throw new IllegalArgumentException("params 值须为字符串");
                            params.put(entry.getKey(),entry.getValue().asString());
                        }
                    }
                    result=data.queryForUser(slug.asString(),current,params);
                }
                case "run_pipeline" -> {
                    fields(arguments,Set.of("pipelineId"));
                    var id=arguments.get("pipelineId");
                    if(id==null || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong()<1)
                        throw new IllegalArgumentException("pipelineId 须为正整数");
                    target+="/pipeline:"+id.asLong();
                    keys.requirePipeline(current,id.asLong());
                    scheduler.run(id.asLong());
                    result=Map.of("pipelineId",id.asLong(),"status","accepted");
                }
                default -> throw new UnknownToolException();
            }
            outcome="SUCCESS";
            return result;
        } catch(ResponseStatusException exception) {
            if(exception.getStatusCode().value()==401 || exception.getStatusCode().value()==403) outcome="DENIED";
            throw exception;
        } finally { platform.audit(identity.username(),"MCP_TOOL_CALL",target,outcome); }
    }
    private Map<String,Object> tool(String name,String description,Map<String,Object> properties,List<String> required,boolean readOnly) {
        return Map.of("name",name,"description",description,"inputSchema",Map.of("type","object","properties",properties,
                "required",required,"additionalProperties",false),"annotations",Map.of("readOnlyHint",readOnly,"destructiveHint",!readOnly,"openWorldHint",false));
    }
    private void fields(JsonNode args,Set<String> allowed) {
        if(!args.isObject()) throw new IllegalArgumentException("arguments 须为对象");
        for(String name:args.propertyNames()) if(!allowed.contains(name)) throw new IllegalArgumentException("包含不支持的工具参数");
    }
    private int integer(JsonNode args,String name,int fallback) {
        var value=args.get(name);
        if(value==null) return fallback;
        if(!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("分页参数须为整数");
        return value.asInt();
    }
    public static class UnknownToolException extends IllegalArgumentException {}
}
