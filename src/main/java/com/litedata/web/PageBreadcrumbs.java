package com.litedata.web;

import java.util.*;
import org.springframework.beans.BeanWrapperImpl;

/** Builds navigation from resolved view models, including failed POST forms, without extra database queries. */
public final class PageBreadcrumbs {
    public record Item(String label,String href) {}
    private record Module(String label,String path) {}
    private static final Map<String,Module> MODULES=Map.ofEntries(
            Map.entry("sources",new Module("数据源","/sources")),Map.entry("models",new Module("数据建模","/models")),
            Map.entry("pipelines",new Module("同步管道","/pipelines")),Map.entry("transforms",new Module("数据加工","/transforms")),
            Map.entry("workflows",new Module("工作流","/workflows")),
            Map.entry("services",new Module("数据服务","/services")),Map.entry("metadata",new Module("元数据与血缘","/metadata")),
            Map.entry("quality",new Module("数据质量","/quality")),Map.entry("metrics",new Module("指标管理","/metrics")),
            Map.entry("assets",new Module("资产目录","/assets")),Map.entry("api-keys",new Module("我的 API Key","/settings/api-keys")),
            Map.entry("users",new Module("账号权限","/users")));

    private PageBreadcrumbs() {}

    public static List<Item> forView(String view,Map<String,Object> model) {
        if("dashboard".equals(view)) return List.of(current("工作台"));
        var items=new ArrayList<Item>(); items.add(link("工作台","/"));
        var module=MODULES.get(view);
        if(module!=null) { items.add(current(module.label())); return List.copyOf(items); }
        switch(view) {
            case "source-form" -> form(items,model,"sources","resourceId","name","新增数据源","编辑数据源",true);
            case "model-form" -> form(items,model,"models","modelId","name","新增模型","编辑模型",false);
            case "pipeline-form" -> form(items,model,"pipelines","resourceId","name","创建同步管道","编辑同步管道",true);
            case "transform-form" -> {
                if("list".equals(model.get("returnTo"))) {
                    items.add(link("数据加工",Objects.toString(model.get("listReturnPath"),"/transforms")));
                    items.add(current(model.get("taskId")==null?"新增数据加工":"编辑数据加工"));
                } else form(items,model,"transforms","taskId","name","新增数据加工","编辑数据加工",false);
            }
            case "workflow-form" -> form(items,model,"workflows","workflowId","name","新建工作流","编辑工作流信息",false);
            case "workflow-canvas" -> detail(items,model,"workflows","workflow");
            case "service-form" -> form(items,model,"services","resourceId","name","创建数据服务","编辑数据服务",true);
            case "metric-form" -> form(items,model,"metrics","metricId","name","新增指标","编辑指标",false);
            case "quality-form" -> form(items,model,"quality","taskId","name","新增质量任务","编辑质量任务",false);
            case "user-form" -> form(items,model,"users","resourceId","username","新增账号","编辑账号",true);
            case "api-key-form" -> page(items,"api-keys","创建 API Key");
            case "api-key-created" -> page(items,"api-keys","API Key 已创建");
            case "metadata-collect" -> page(items,"metadata","采集元数据");
            case "model-detail" -> detail(items,model,"models","definition");
            case "transform-detail" -> detail(items,model,"transforms","task");
            case "quality-detail" -> detail(items,model,"quality","task");
            case "metric-detail" -> metric(items,model,false);
            case "metric-lineage" -> metric(items,model,true);
            case "metadata-detail", "metadata-lineage" -> metadata(items,model,view.endsWith("lineage"));
            case "asset-detail", "asset-form" -> asset(items,model,view.endsWith("form"));
            case "pipeline-runs" -> {
                records(items,model,"pipelines","pipeline","历史未关联回调");
                items.add(items.size()-1,link("运行实例","/scheduling/pipeline/"+number(property(model.get("pipeline"),"id"))));
            }
            case "service-calls" -> records(items,model,"services","service","调用记录");
            case "quality-run-detail" -> {
                parent(items,"quality"); var task=model.get("task");
                items.add(link(name(task,"name","质量任务"),resource("quality",property(task,"id"))));
                items.add(current("质量运行 #"+number(property(model.get("run"),"id"))));
            }
            case "scheduler-instances", "scheduler-instance-detail" -> scheduler(items,model,"scheduler-instance-detail".equals(view));
            default -> { return List.of(); }
        }
        return List.copyOf(items);
    }

    private static void form(List<Item> items,Map<String,Object> model,String module,String idKey,String nameKey,
                             String create,String edit,boolean listAnchor) {
        parent(items,module); Object id=model.get(idKey);
        if(id!=null) items.add(link(name(model.get("draft"),nameKey,"资源 #"+number(id)),listAnchor?row(module,id):resource(module,id)));
        items.add(current(id==null?create:edit));
    }
    private static void page(List<Item> items,String module,String operation) { parent(items,module); items.add(current(operation)); }
    private static void detail(List<Item> items,Map<String,Object> model,String module,String key) {
        parent(items,module); items.add(current(name(model.get(key),"name","资源详情")));
    }
    private static void records(List<Item> items,Map<String,Object> model,String module,String key,String operation) {
        parent(items,module); Object entity=model.get(key);
        items.add(link(name(entity,"name","同步任务"),row(module,property(entity,"id"))));
        items.add(current(operation));
    }
    private static void metadata(List<Item> items,Map<String,Object> model,boolean lineage) {
        parent(items,"metadata"); var table=model.get("table");
        String label=name(table,"catalogName","")+"."+name(table,"tableName","表详情");
        if(lineage) { items.add(link(label,resource("metadata",property(table,"id")))); items.add(current("表级血缘")); }
        else items.add(current(label));
    }
    private static void asset(List<Item> items,Map<String,Object> model,boolean edit) {
        parent(items,"assets"); var asset=model.get("asset"); String label=name(asset,"name","资产详情");
        if(edit) {
            // Asset paths are constructed by the catalog from its enum type and numeric resource ID.
            String path=Objects.toString(property(asset,"path"),"/assets");
            items.add(link(label,path)); items.add(current("编辑目录信息"));
        } else items.add(current(label));
    }
    private static void metric(List<Item> items,Map<String,Object> model,boolean lineage) {
        parent(items,"metrics"); var definition=model.get("definition");
        String path=resource("metrics",property(definition,"id")), label=name(definition,"name","指标详情");
        String version="历史版本 v"+number(property(definition,"version"));
        String versionPath=path+"/versions/"+number(property(definition,"version"));
        boolean historical=Boolean.TRUE.equals(model.get("historical")) || versionPath.equals(model.get("backPath"));
        if(lineage || historical) items.add(link(label,path)); else items.add(current(label));
        if(historical) items.add(lineage?link(version,versionPath):current(version));
        if(lineage) items.add(current("指标血缘"));
    }
    private static void scheduler(List<Item> items,Map<String,Object> model,boolean instance) {
        var resource=model.get("resource"); boolean pipeline="pipeline".equals(property(resource,"type"));
        boolean workflow="workflow".equals(property(resource,"type"));
        String module=pipeline?"pipelines":workflow?"workflows":"transforms"; Object id=property(resource,"id");
        parent(items,module);
        items.add(link(name(resource,"name","任务"),pipeline?row(module,id):resource(module,id)));
        if(instance) {
            items.add(link("运行实例","/scheduling/"+(pipeline?"pipeline":workflow?"workflow":"transform")+"/"+number(id)));
            items.add(current("实例 #"+number(model.get("instanceId"))));
        } else items.add(current("运行实例"));
    }
    private static void parent(List<Item> items,String key) { var module=MODULES.get(key); items.add(link(module.label(),module.path())); }
    private static String resource(String module,Object id) { return MODULES.get(module).path()+"/"+number(id); }
    private static String row(String module,Object id) {
        String prefix=switch(module) { case "sources" -> "source"; case "pipelines" -> "pipeline"; case "services" -> "service"; case "users" -> "user"; default -> throw new IllegalArgumentException("Unsupported list anchor"); };
        return MODULES.get(module).path()+"#"+prefix+"-"+number(id);
    }
    private static String number(Object value) { return value instanceof Number number?Long.toString(number.longValue()):"0"; }
    private static String name(Object bean,String property,String fallback) {
        Object value=property(bean,property); return value instanceof String text && !text.isBlank()?text:fallback;
    }
    private static Object property(Object bean,String name) {
        if(bean==null) return null;
        if(bean instanceof Map<?,?> map) return map.get(name);
        var wrapper=new BeanWrapperImpl(bean); return wrapper.isReadableProperty(name)?wrapper.getPropertyValue(name):null;
    }
    private static Item current(String label) { return new Item(label,null); }
    private static Item link(String label,String href) {
        if(!href.startsWith("/") || href.startsWith("//") || href.indexOf('\\')>=0 || href.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Breadcrumb links must be internal paths");
        return new Item(label,href);
    }
}
