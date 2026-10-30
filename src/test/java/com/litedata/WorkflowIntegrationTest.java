package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.data.SourceService;
import com.litedata.model.*;
import com.litedata.modeling.*;
import com.litedata.pipeline.PipelineService;
import com.litedata.scheduling.DolphinMonitorClient;
import com.litedata.scheduling.SchedulerOperations;
import com.litedata.store.*;
import com.litedata.transform.*;
import com.litedata.workflow.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real Flyway/MySQL MVC coverage; Doris and DolphinScheduler are isolated boundaries. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkflowIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("workflow-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername); r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("platform.encryption-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token",()->"workflow-test-internal-token-0123456789"); r.add("platform.admin-password",()->"WorkflowAdminPassword2026");
        r.add("platform.doris.jdbc-url",MYSQL::getJdbcUrl); r.add("platform.doris.username",MYSQL::getUsername); r.add("platform.doris.password",MYSQL::getPassword);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired WorkflowService workflows; @Autowired WorkflowMapper mapper;
    @Autowired TransformService tasks; @Autowired ModelingService models; @Autowired SourceService sources; @Autowired PipelineService pipelines; @Autowired PlatformMapper platform; @Autowired SchedulerOperations schedulerOperations;
    @MockitoBean DorisModelGateway doris; @MockitoBean DolphinWorkflowClient engine; @MockitoBean DolphinMonitorClient monitor;
    private final JsonMapper json=JsonMapper.builder().build();

    @BeforeEach void prepare() throws Exception {
        for(String table:List.of("processing_workflow_snapshot","processing_workflow","sql_task_run","sql_task_dependency","sql_pipeline_dependency","sql_task","model_execution","model_field","data_model","pipeline_run","pipeline","data_source","audit_event")) jdbc.update("DELETE FROM "+table);
        reset(doris,engine,monitor);
        when(doris.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id","bigint",false,"")),List.of("id"),List.of("id"),1));
        when(engine.configured()).thenReturn(true); when(engine.create(anyString(),anyString(),anyList())).thenReturn(801L); when(engine.createSchedule(anyLong(),anyString())).thenReturn(91);
    }

    @Test void createsNamedWorkflowsAndPagesDatabaseResults() throws Exception {
        long first=workflow("orders-close",task("ads_orders",List.of()),"订单月末");
        workflow("inventory-close",task("ads_inventory",List.of()),"库存月末");
        assertThat(workflows.count("orders")).isEqualTo(1);
        assertThat(workflows.list("close",new com.litedata.service.QueryPage(2,1))).extracting(ProcessingWorkflow::getName).containsExactly("orders-close");
        mvc.perform(get("/workflows").param("keyword","inventory").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("inventory-close")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("orders-close"))));
        mvc.perform(get("/workflows/"+first).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("workflow-canvas")));
        mvc.perform(get("/workflows/new").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk());
        mvc.perform(get("/workflows/"+first+"/edit").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk());
        mvc.perform(get("/workflows/new").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/workflows/"+first+"/edit").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test void savesDiamondAndPipelineDependenciesThenRemovingNodeKeepsTask() {
        long pipeline=pipeline("ods"); long a=task("dwd_a",List.of()), b=task("dws_b",List.of(a)), c=task("dws_c",List.of(a)), root=task("ads_root",List.of(b,c));
        long id=workflow("diamond",root,""); var view=workflows.view(id);
        var canvas=new ArrayList<>(view.nodes()); canvas.add(workflows.expand(id,"pipeline-"+pipeline).getFirst());
        var input=save(view,canvas,edges("sql-"+a,"sql-"+b,"sql-"+a,"sql-"+c,"sql-"+b,"sql-"+root,"sql-"+c,"sql-"+root,"pipeline-"+pipeline,"sql-"+a));
        var saved=workflows.save(id,input,"dev");
        assertThat(saved.nodes()).extracting(WorkflowService.Node::key).contains("pipeline-"+pipeline,"sql-"+a,"sql-"+b,"sql-"+c,"sql-"+root);
        var reduced=workflows.save(id,save(saved,saved.nodes().stream().filter(n->!n.key().equals("sql-"+c)).toList(),edges("pipeline-"+pipeline,"sql-"+a,"sql-"+a,"sql-"+b,"sql-"+b,"sql-"+root)),"dev");
        assertThat(reduced.nodes()).extracting(WorkflowService.Node::key).doesNotContain("sql-"+c);
        assertThat(tasks.require(c).getId()).isEqualTo(c); // removed from this canvas, not archived/deleted
    }

    @Test void rejectsCycleOrphansAndPipelineDownstreamWithoutPartialWrites() {
        long pipe=pipeline("source"); long a=task("dwd_a",List.of()), root=task("ads_root",List.of(a)); long id=workflow("guard",root,""); var view=workflows.view(id);
        var baseline=tasks.graph(root).hash();
        assertThatIllegalArgumentException().isThrownBy(()->workflows.save(id,save(view,view.nodes(),List.of(new WorkflowService.Edge("sql-"+root,"sql-"+a),new WorkflowService.Edge("sql-"+a,"sql-"+root))),"dev"));
        var withPipeline=new ArrayList<>(view.nodes()); withPipeline.add(workflows.expand(id,"pipeline-"+pipe).getFirst());
        assertThatIllegalArgumentException().isThrownBy(()->workflows.save(id,save(view,withPipeline,List.of(new WorkflowService.Edge("sql-"+root,"pipeline-"+pipe))),"dev"));
        var orphan=new WorkflowService.Node("sql-"+a,"a","SQL",a,tasks.require(a).getVersion(),List.of(),"dwd_a","","",tasks.graph(a).hash());
        assertThatIllegalArgumentException().isThrownBy(()->workflows.save(id,save(view,List.of(view.nodes().stream().filter(n->n.key().equals("sql-"+root)).findFirst().orElseThrow(),orphan),List.of()),"dev"));
        assertThat(tasks.graph(root).hash()).isEqualTo(baseline);
    }

    @Test void rejectsStaleVersionOrGraphJsonAndReturnsJsonErrors() throws Exception {
        long root=task("ads_root",List.of()); long id=workflow("stale",root,""); var view=workflows.view(id);
        var stale=save(view,view.nodes(),List.of());
        var rename=draft("renamed",root,""); rename.setVersion(view.version());
        workflows.metadata(id,rename,"dev");
        assertThatThrownBy(()->workflows.save(id,stale,"dev")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e->((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(409);
        var current=workflows.view(id); var changed=tasks.require(root);
        changed.setSqlText("INSERT INTO ads_root SELECT id FROM ods_orders WHERE id > 0"); tasks.save(root,changed,"dev");
        assertThatThrownBy(()->workflows.save(id,save(current,current.nodes(),List.of()),"dev")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e->((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(409);
        String body=json.writeValueAsString(stale);
        mvc.perform(post("/workflows/"+id+"/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/workflows/"+id+"/graph").with(user("viewer").roles("VIEWER")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/workflows/"+id+"/graph").with(user("dev").roles("DEVELOPER")).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }

    @Test void scheduledSharedUpstreamCannotChangeButLayoutOnlySaveKeepsSqlVersions() {
        long shared=task("dwd_shared",List.of()), root=task("ads_root",List.of(shared)); long id=workflow("scheduled",root,""); var view=workflows.view(id);
        workflows.execute(id,view.version(),view.graphHash(),"schedule","dev");
        var current=workflows.view(id); int rootVersion=tasks.require(root).getVersion(),sharedVersion=tasks.require(shared).getVersion();
        var layoutOnly=new WorkflowService.Save(current.version(),current.graphHash(),current.cronExpression(),current.nodes(),edgesFrom(current),Map.of("sql-"+root,new WorkflowService.Position(120,80)));
        workflows.save(id,layoutOnly,"dev");
        assertThat(tasks.require(root).getVersion()).isEqualTo(rootVersion); assertThat(tasks.require(shared).getVersion()).isEqualTo(sharedVersion);
        var noShared=workflows.view(id);
        assertThatIllegalArgumentException().isThrownBy(()->workflows.save(id,save(noShared,noShared.nodes().stream().filter(n->!n.key().equals("sql-"+shared)).toList(),List.of()),"dev"));
    }

    @Test void workflowRunScheduleAndPauseOperateCurrentCompleteRootGraph() throws Exception {
        long a=task("dwd_a",List.of()), root=task("ads_root",List.of(a)); long id=workflow("operate",root,""); var view=workflows.view(id);
        mvc.perform(post("/workflows/"+id+"/run").with(user("dev").roles("DEVELOPER")).with(csrf()).param("version",String.valueOf(view.version())).param("graphHash",view.graphHash()))
                .andExpect(status().is3xxRedirection());
        verify(engine).create(anyString(),eq("ads_root"),argThat(nodes->nodes.size()==2)); verify(engine).start(801L);
        view=workflows.view(id);
        mvc.perform(post("/workflows/"+id+"/schedule").with(user("dev").roles("DEVELOPER")).with(csrf()).param("version",String.valueOf(view.version())).param("graphHash",view.graphHash())).andExpect(status().is3xxRedirection());
        view=workflows.view(id);
        jdbc.update("UPDATE sql_task SET sql_text=CONCAT(sql_text,' ') WHERE id=?",root); // simulate a now-stale canvas after a model/graph change
        mvc.perform(post("/workflows/"+id+"/pause").with(user("dev").roles("DEVELOPER")).with(csrf()).param("version",String.valueOf(view.version())).param("graphHash",view.graphHash())).andExpect(status().is3xxRedirection());
        verify(engine).offline(91);
    }

    @Test void taskEditorInWorkflowContextPreservesGraphFieldsAndReturnsToCanvas() throws Exception {
        long upstream=task("dwd_editor",List.of()), root=task("ads_editor",List.of(upstream)); long workflow=workflow("editor",root,"");
        var before=tasks.require(root);
        mvc.perform(post("/transforms/"+root).with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("workflowId",Long.toString(workflow)).param("name",before.getName())
                        .param("modelId",Long.toString(before.getModelId())).param("sqlText",before.getSqlText()+" ")
                        .param("version",Integer.toString(before.getVersion())))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/workflows/"+workflow));
        var saved=tasks.require(root);
        assertThat(saved.getCronExpression()).isEqualTo(before.getCronExpression());
        assertThat(saved.getUpstreamTaskIds()).containsExactly(upstream);
        assertThat(saved.getUpstreamPipelineIds()).isEmpty();
    }

    @Test void preservesHistoricalRunGraphsAndOnlyExposesInstancesForTheirWorkflow() {
        long upstream=task("dwd_history",List.of()), root=task("ads_history",List.of(upstream)); long id=workflow("history",root,"");
        when(engine.create(anyString(),anyString(),anyList())).thenReturn(801L,802L);
        var first=workflows.view(id); workflows.execute(id,first.version(),first.graphHash(),"run","dev");
        var before=schedulerOperations.resource("workflow",id);
        assertThat(schedulerOperations.graph(before,801L)).extracting(WorkflowService.Node::key).containsExactly("sql-"+upstream,"sql-"+root);
        var current=workflows.view(id);
        workflows.save(id,save(current,List.of(current.nodes().stream().filter(n->n.key().equals("sql-"+root)).findFirst().orElseThrow()),List.of()),"dev");
        var second=workflows.view(id); workflows.execute(id,second.version(),second.graphHash(),"run","dev");
        var resource=schedulerOperations.resource("workflow",id);
        assertThat(resource.codes()).contains(801L,802L);
        assertThat(schedulerOperations.graph(resource,801L)).extracting(WorkflowService.Node::key).containsExactly("sql-"+upstream,"sql-"+root);
        assertThat(schedulerOperations.graph(resource,802L)).extracting(WorkflowService.Node::key).containsExactly("sql-"+root);
        when(monitor.instance(51L)).thenReturn(new DolphinMonitorClient.Workflow(51L,801L,"history","SUCCESS","","",1));
        assertThat(schedulerOperations.instance(resource,51L).definitionCode()).isEqualTo(801L);
        when(monitor.instance(52L)).thenReturn(new DolphinMonitorClient.Workflow(52L,999L,"foreign","SUCCESS","","",1));
        assertThatThrownBy(()->schedulerOperations.instance(resource,52L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e->((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(404);
    }

    @Test void legacyDefinitionWithoutSnapshotIsReusedWithoutInventingHistoricalGraph() {
        long root=task("ads_legacy",List.of()); long id=workflow("legacy",root,""); var view=workflows.view(id);
        jdbc.update("UPDATE sql_task SET definition_code=?,definition_hash=? WHERE id=?",734L,view.graphHash(),root);
        workflows.execute(id,view.version(),view.graphHash(),"run","dev");
        verify(engine,never()).create(anyString(),anyString(),anyList()); verify(engine).start(734L);
        var resource=schedulerOperations.resource("workflow",id);
        assertThat(resource.codes()).containsExactly(734L);
        assertThat(mapper.snapshotCodes(root)).isEmpty();
        assertThat(schedulerOperations.graph(resource,734L)).isNull();
    }

    private ProcessingWorkflow draft(String name,long root,String description) { var value=new ProcessingWorkflow(); value.setName(name); value.setRootTaskId(root); value.setDescription(description); return value; }
    private long workflow(String name,long root,String description) { return workflows.metadata(null,draft(name,root,description),"dev"); }
    private WorkflowService.Save save(WorkflowService.View view,List<WorkflowService.Node> nodes,List<WorkflowService.Edge> edges) { return new WorkflowService.Save(view.version(),view.graphHash(),view.cronExpression(),nodes,edges,Map.of()); }
    private List<WorkflowService.Edge> edges(String... values) { var result=new ArrayList<WorkflowService.Edge>(); for(int i=0;i<values.length;i+=2) result.add(new WorkflowService.Edge(values[i],values[i+1])); return result; }
    private List<WorkflowService.Edge> edgesFrom(WorkflowService.View view) { return view.nodes().stream().flatMap(n->n.upstreamKeys().stream().map(key->new WorkflowService.Edge(key,n.key()))).toList(); }
    private long pipeline(String name) { sources.create("source-"+name,MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()); models.save(null,model("ods_"+name,"ODS"),"dev"); pipelines.create(name,platform.sources().getFirst().getId(),"orders","updated_at","ods_"+name,"","0 0 * * * ?"); return platform.pipelines().getFirst().getId(); }
    private long task(String table,List<Long> upstream) { long model=models.save(null,model(table,table.startsWith("ads")?"ADS":table.startsWith("dws")?"DWS":"DWD"),"dev"); var task=new SqlTask(); task.setName(table); task.setModelId(model); task.setSqlText("INSERT INTO "+table+" SELECT id FROM ods_orders"); task.setUpstreamTaskIds(upstream); return tasks.save(null,task,"dev"); }
    private DataModel model(String table,String layer) { var value=new DataModel(); value.setName(table);value.setTableName(table);value.setSubjectArea("测试");value.setLayer(layer);var field=new ModelField();field.setName("id");field.setDataType("BIGINT");field.setKeyColumn(true);value.setFields(List.of(field));return value; }
}
