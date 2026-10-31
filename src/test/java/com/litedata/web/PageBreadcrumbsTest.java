package com.litedata.web;

import static org.assertj.core.api.Assertions.*;

import com.litedata.scheduling.SchedulerOperations;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PageBreadcrumbsTest {
    @Test void everyApplicationViewHasNavigableParentsAndExactlyOneCurrentItem() throws Exception {
        try(var files=Files.list(Path.of("src/main/resources/templates"))) {
            for(var file:files.filter(path->path.toString().endsWith(".html") && !path.getFileName().toString().equals("login.html")).toList()) {
                String view=file.getFileName().toString().replace(".html","");
                var items=PageBreadcrumbs.forView(view,model());
                assertThat(items).as(view).isNotEmpty();
                assertThat(items.stream().filter(item->item.href()==null).count()).as(view).isEqualTo(1);
                assertThat(items.getLast().href()).as(view).isNull();
                assertThat(items.subList(0,items.size()-1)).allMatch(item->item.href()!=null && item.href().startsWith("/") && !item.href().startsWith("//"));
            }
        }
    }

    @Test void editsAndRecordPagesReturnToRealResourcePagesOrListRows() {
        var items=PageBreadcrumbs.forView("pipeline-runs",model());
        assertThat(items).extracting(PageBreadcrumbs.Item::href).containsExactly("/","/pipelines","/pipelines#pipeline-7","/scheduling/pipeline/7",null);
        assertThat(items.getLast().label()).isEqualTo("历史未关联回调");
        assertThat(PageBreadcrumbs.forView("source-form",model()).get(2).href()).isEqualTo("/sources#source-7");
        assertThat(PageBreadcrumbs.forView("model-form",model()).get(2).href()).isEqualTo("/models/7");
        assertThat(PageBreadcrumbs.forView("asset-form",model()).get(2).href()).isEqualTo("/assets/MODEL/7");
        var create=model(); create.remove("modelId");
        assertThat(PageBreadcrumbs.forView("model-form",create)).extracting(PageBreadcrumbs.Item::label).containsExactly("工作台","数据建模","新增模型");
    }

    @Test void transformEditorBreadcrumbsFollowItsEntryPoint() {
        var list=model(); list.put("returnTo","list"); list.put("listReturnPath","/transforms?pageNum=2&pageSize=50");
        assertThat(PageBreadcrumbs.forView("transform-form",list)).extracting(PageBreadcrumbs.Item::href)
                .containsExactly("/","/transforms?pageNum=2&pageSize=50",null);
        var detail=model(); detail.put("returnTo","detail");
        assertThat(PageBreadcrumbs.forView("transform-form",detail)).extracting(PageBreadcrumbs.Item::href)
                .containsExactly("/","/transforms","/transforms/7",null);
    }

    @Test void historicalLineagePreservesTheVersionAndSchedulerNodesPreserveTaskIdentity() {
        var history=model(); history.put("backPath","/metrics/7/versions/3");
        assertThat(PageBreadcrumbs.forView("metric-lineage",history)).extracting(PageBreadcrumbs.Item::href)
                .containsExactly("/","/metrics","/metrics/7","/metrics/7/versions/3",null);
        assertThat(PageBreadcrumbs.forView("metric-detail",history).getLast().label()).isEqualTo("历史版本 v3");
        var current=model(); current.put("historical",false); current.put("backPath","/metrics/7");
        assertThat(PageBreadcrumbs.forView("metric-lineage",current)).hasSize(4);
        assertThat(PageBreadcrumbs.forView("scheduler-instance-detail",model())).extracting(PageBreadcrumbs.Item::href)
                .containsExactly("/","/pipelines","/pipelines#pipeline-7","/scheduling/pipeline/7",null);
        current.put("resource",new SchedulerOperations.Resource("transform",8,"加工任务","/transforms/8",List.of(99L)));
        assertThat(PageBreadcrumbs.forView("scheduler-instances",current)).extracting(PageBreadcrumbs.Item::href)
                .containsExactly("/","/transforms","/transforms/8",null);
    }

    @Test void navigationDoesNotReadCredentialsAndRejectsExternalParentLinks() {
        var attributes=model(); attributes.put("apiKey","do-not-expose");
        assertThat(PageBreadcrumbs.forView("api-key-created",attributes)).noneMatch(item->item.label().contains("do-not-expose"));
        attributes.put("asset",Map.of("name","asset","path","https://untrusted.example"));
        assertThatIllegalArgumentException().isThrownBy(()->PageBreadcrumbs.forView("asset-form",attributes));
        assertThat(PageBreadcrumbs.forView("login",attributes)).isEmpty();
        assertThat(PageBreadcrumbs.forView("unknown-view",attributes)).isEmpty();
    }

    private static Map<String,Object> model() {
        var entity=Map.of("id",7L,"name","资源名称","username","test-user","catalogName","warehouse","tableName","orders","version",3,"path","/assets/MODEL/7");
        var attributes=new HashMap<String,Object>();
        for(String key:List.of("draft","pipeline","service","table","asset","task","run","definition")) attributes.put(key,entity);
        for(String key:List.of("resourceId","modelId","metricId","taskId")) attributes.put(key,7L);
        attributes.put("resource",new SchedulerOperations.Resource("pipeline",7,"同步任务","/pipelines",List.of(99L)));
        attributes.put("instanceId",42L); attributes.put("historical",true);
        return attributes;
    }
}
