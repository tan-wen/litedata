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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Workflow-local DAG and scheduling ownership backed by real Flyway/MySQL. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class WorkflowIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4").withDatabaseName("platform").withUsername("platform").withPassword("workflow-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername); r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("platform.encryption-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="); r.add("platform.internal-token",()->"workflow-test-internal-token-0123456789"); r.add("platform.admin-password",()->"WorkflowAdminPassword2026");
        r.add("platform.doris.jdbc-url",MYSQL::getJdbcUrl); r.add("platform.doris.username",MYSQL::getUsername); r.add("platform.doris.password",MYSQL::getPassword);
    }
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired WorkflowService workflows; @Autowired TransformService tasks; @Autowired ModelingService models; @Autowired SourceService sources; @Autowired PipelineService pipelines; @Autowired PlatformMapper platform;
    @Autowired com.litedata.scheduling.SchedulerOperations operations;
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    @MockitoBean DorisModelGateway doris; @MockitoBean DolphinWorkflowClient engine; @MockitoBean DolphinMonitorClient monitor;
    @BeforeEach void prepare() throws Exception {
        for(String table:List.of("sql_task_run","workflow_definition_snapshot","processing_workflow_snapshot","processing_workflow_edge","processing_workflow_node","processing_workflow","pipeline_transform_archive","sql_task_dependency","sql_pipeline_dependency","sql_task","model_execution","model_field","data_model","pipeline_run","pipeline","data_source","audit_event")) jdbc.update("DELETE FROM "+table);
        reset(doris,engine,monitor); when(doris.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id","bigint",false,"")),List.of("id"),List.of("id"),1));
        when(engine.configured()).thenReturn(true); when(engine.create(anyString(),anyString(),anyList())).thenReturn(801L,802L); when(engine.createSchedule(anyLong(),anyString())).thenReturn(91);
    }
    @Test void createsEmptyWorkflowThenBuildsIndependentMultiOutputCanvas() {
        long flow=workflow("月末加工"); var empty=workflows.view(flow); assertThat(empty.nodes()).isEmpty();
        long a=task("dwd_orders"), b=task("ads_orders"), c=task("ads_inventory");
        var saved=workflows.save(flow,save(empty,List.of(node(flow,a),node(flow,b),node(flow,c)),edges("sql-"+a,"sql-"+b,"sql-"+a,"sql-"+c)),"dev");
        assertThat(saved.nodes()).hasSize(3); assertThat(saved.nodes().stream().filter(n->n.upstreamKeys().isEmpty())).hasSize(1);
        long other=workflow("另一个流程"); var second=workflows.save(other,save(workflows.view(other),List.of(node(other,b)),List.of()),"dev");
        assertThat(second.nodes()).extracting(WorkflowService.Node::key).containsExactly("sql-"+b);
        var reduced=workflows.save(flow,save(saved,List.of(node(flow,a),node(flow,b)),edges("sql-"+a,"sql-"+b)),"dev");
        assertThat(reduced.nodes()).hasSize(2); assertThat(tasks.require(c).getId()).isEqualTo(c);
    }
    @Test void inlineSqlSaveAndScheduleAreWorkflowOwned() throws Exception {
        long flow=workflow("画布开发"); var blank=workflows.view(flow); long model=model("dwd_inline","DWD");
        var created=workflows.saveTask(flow,new WorkflowService.TaskSave(blank.version(),blank.graphHash(),null,0,"画布 SQL",model,"INSERT INTO dwd_inline SELECT id FROM ods_orders"),"dev");
        long id=created.nodes().getFirst().refId(); assertThat(workflows.task(flow,id).sqlText()).contains("dwd_inline");
        mvc.perform(get("/workflows/"+flow+"/sql-tasks/"+id).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/workflows/"+flow+"/sql-tasks").with(user("viewer").roles("VIEWER")).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
        workflows.execute(flow,created.version(),created.graphHash(),"schedule","dev");
        assertThat(workflows.require(flow).getStatus()).isEqualTo("SCHEDULED"); assertThat(workflows.require(flow).getScheduleId()).isEqualTo(91);
        var scheduled=workflows.view(flow);
        assertThatIllegalArgumentException().isThrownBy(()->workflows.save(flow,new WorkflowService.Save(scheduled.version(),scheduled.graphHash(),"0 */5 * * * ?",scheduled.nodes(),List.of(),Map.of()),"dev")).withMessageContaining("暂停");
        workflows.execute(flow,workflows.view(flow).version(),"","pause","dev"); assertThat(workflows.require(flow).getStatus()).isEqualTo("PAUSED");
    }
    @Test void pipelineStandaloneScheduleIsVisibleButWorkflowUsesItsOwnCron() {
        long pipe=pipeline("ods_orders"); jdbc.update("UPDATE pipeline SET schedule_id=44,status='SCHEDULED' WHERE id=?",pipe);
        long sql=task("dwd_orders"); long flow=workflow("含同步"); var view=workflows.view(flow);
        var saved=workflows.save(flow,save(view,List.of(workflows.expand(flow,"pipeline-"+pipe).getFirst(),node(flow,sql)),edges("pipeline-"+pipe,"sql-"+sql)),"dev");
        assertThat(saved.nodes().stream().filter(n->n.key().equals("pipeline-"+pipe)).findFirst().orElseThrow().standaloneScheduled()).isTrue();
        workflows.execute(flow,saved.version(),saved.graphHash(),"schedule","dev");
        verify(engine).createSchedule(801L,saved.cronExpression());
    }
    @Test void protectsGraphEndpointsWithRolesAndCsrf() throws Exception {
        long flow=workflow("权限"); String body="{\"version\":1,\"graphHash\":\"x\",\"cronExpression\":\"0 0 * * * ?\",\"nodes\":[],\"edges\":[]}";
        mvc.perform(get("/workflows/"+flow).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(post("/workflows/"+flow+"/graph").with(user("viewer").roles("VIEWER")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/workflows/"+flow+"/graph").with(user("dev").roles("DEVELOPER")).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
    @Test void rejectsCyclesAndPipelineDownstreamWithoutChangingStoredCanvas() {
        long flow=workflow("图校验"), pipe=pipeline("ods_graph"), a=task("dwd_graph"), b=task("ads_graph");
        var base=workflows.view(flow); var nodes=List.of(workflows.expand(flow,"pipeline-"+pipe).getFirst(),node(flow,a),node(flow,b));
        var saved=workflows.save(flow,save(base,nodes,edges("pipeline-"+pipe,"sql-"+a,"sql-"+a,"sql-"+b)),"dev");
        assertThatIllegalArgumentException().isThrownBy(()->workflows.save(flow,save(saved,nodes,edges("sql-"+a,"pipeline-"+pipe)),"dev")).withMessageContaining("同步");
        assertThatIllegalArgumentException().isThrownBy(()->workflows.save(flow,save(saved,nodes,edges("sql-"+a,"sql-"+b,"sql-"+b,"sql-"+a)),"dev")).withMessageContaining("循环");
        assertThat(workflows.view(flow).graphHash()).isEqualTo(saved.graphHash());
    }
    @Test void taskPageHasNoStandaloneScheduleActions() throws Exception {
        long id=task("dwd_page");
        mvc.perform(get("/transforms/"+id).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("调度周期"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("启用调度"))));
        for(String action:List.of("run","schedule","pause")) mvc.perform(post("/transforms/"+id+"/"+action).with(user("dev").roles("DEVELOPER")).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void staleCanvasAndInlineSqlEditsAreRejectedWithoutPartialWrites() throws Exception {
        long flow=workflow("并发"); long sql=task("dwd_stale");
        var view=workflows.save(flow,save(workflows.view(flow),List.of(node(flow,sql)),List.of()),"dev");
        var task=workflows.task(flow,sql);
        var input=new WorkflowService.TaskSave(view.version(),view.graphHash(),sql,task.version(),task.name(),task.modelId(),"INSERT INTO dwd_stale SELECT id FROM ods_orders WHERE id>0");
        mvc.perform(post("/workflows/"+flow+"/sql-tasks").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodes[0].version").value(task.version()+1));
        mvc.perform(post("/workflows/"+flow+"/sql-tasks").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isConflict());
        mvc.perform(post("/workflows/"+flow+"/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(save(view,view.nodes(),List.of()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty());
        var current=workflows.view(flow); int version=tasks.require(sql).getVersion();
        var invalid=new WorkflowService.TaskSave(current.version(),current.graphHash(),sql,version,task.name(),task.modelId(),"DELETE FROM dwd_stale");
        mvc.perform(post("/workflows/"+flow+"/sql-tasks").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
        assertThat(tasks.require(sql).getVersion()).isEqualTo(version);
        assertThat(workflows.require(flow).getVersion()).isEqualTo(current.version());
        long foreign=task("ads_foreign");
        mvc.perform(get("/workflows/"+flow+"/sql-tasks/"+foreign).with(user("dev").roles("DEVELOPER"))).andExpect(status().isNotFound());
    }

    @Test void snapshotsKeepHistoricalTopologyAndRejectForeignInstances() {
        long a=task("dwd_history"),b=task("ads_history"),flow=workflow("历史图");
        var first=workflows.save(flow,save(workflows.view(flow),List.of(node(flow,a),node(flow,b)),edges("sql-"+a,"sql-"+b)),"dev");
        workflows.execute(flow,first.version(),first.graphHash(),"run","dev");
        var second=workflows.save(flow,save(workflows.view(flow),List.of(node(flow,b)),List.of()),"dev");
        workflows.execute(flow,second.version(),second.graphHash(),"run","dev");
        var resource=operations.resource("workflow",flow);
        assertThat(resource.codes()).contains(801L,802L);
        assertThat(operations.graph(resource,801L)).extracting(WorkflowService.Node::key).containsExactly("sql-"+a,"sql-"+b);
        assertThat(operations.graph(resource,802L)).extracting(WorkflowService.Node::key).containsExactly("sql-"+b);
        when(monitor.instance(52L)).thenReturn(new com.litedata.scheduling.DolphinMonitorClient.Workflow(52L,999L,"foreign","SUCCESS","","",1));
        assertThatThrownBy(()->operations.instance(resource,52L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(engine,times(2)).create(anyString(),anyString(),argThat(nodes->nodes.stream().allMatch(n->n.script().contains("--workflow-id "+flow)&&!n.script().contains("INSERT"))));
    }

    @Test void sharedSqlIsProtectedByScheduledWorkflowWhileOtherGraphsStayIndependent() {
        long shared=task("dwd_shared"),sink=task("ads_shared"),owner=workflow("owner"),other=workflow("other");
        var active=workflows.save(owner,save(workflows.view(owner),List.of(node(owner,shared),node(owner,sink)),edges("sql-"+shared,"sql-"+sink)),"dev");
        workflows.execute(owner,active.version(),active.graphHash(),"schedule","dev");
        var second=workflows.save(other,save(workflows.view(other),List.of(node(other,shared),node(other,sink)),List.of()),"dev");
        int version=tasks.require(shared).getVersion();
        assertThatIllegalArgumentException().isThrownBy(()->workflows.saveTask(other,new WorkflowService.TaskSave(second.version(),second.graphHash(),shared,version,"changed",tasks.require(shared).getModelId(),tasks.require(shared).getSqlText()),"dev"));
        var layout=new WorkflowService.Save(active.version(),active.graphHash(),active.cronExpression(),active.nodes(),edges("sql-"+shared,"sql-"+sink),Map.of("sql-"+sink,new WorkflowService.Position(200,50)));
        var saved=workflows.save(owner,layout,"dev");
        assertThat(saved.graphHash()).isEqualTo(active.graphHash()); assertThat(tasks.require(shared).getVersion()).isEqualTo(version);
        assertThat(workflows.view(other).nodes()).allMatch(n->n.upstreamKeys().isEmpty());
        assertThat(workflows.view(owner).nodes()).anyMatch(n->n.upstreamKeys().contains("sql-"+shared));
    }

    @Test void workflowClaimBindsRunToWorkflowAndRejectsChangedGraph() {
        long sql=task("dwd_claim"),flow=workflow("claim");
        var view=workflows.save(flow,save(workflows.view(flow),List.of(node(flow,sql)),List.of()),"dev");
        long run=((Number)tasks.claim(sql,tasks.require(sql).getVersion(),null,flow,view.graphHash()).get("runId")).longValue();
        var mapperRun=jdbc.queryForMap("SELECT workflow_id,root_task_id FROM sql_task_run WHERE id=?",run);
        assertThat(mapperRun).containsEntry("workflow_id",flow).containsEntry("root_task_id",null);
        tasks.finish(run,true);
        var removed=workflows.save(flow,save(workflows.view(flow),List.of(),List.of()),"dev");
        assertThatThrownBy(()->tasks.claim(sql,tasks.require(sql).getVersion(),null,flow,view.graphHash())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatIllegalArgumentException().isThrownBy(()->workflows.execute(flow,removed.version(),removed.graphHash(),"run","dev"));
    }

    @Test void emptyCreationHasNoRootAndPauseWorksEvenForBrokenSql() throws Exception {
        mvc.perform(get("/workflows/new").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("rootTaskId"))));
        mvc.perform(post("/workflows").with(user("dev").roles("DEVELOPER")).with(csrf()).param("name","empty-form"))
                .andExpect(status().is3xxRedirection());
        long sql=task("dwd_broken"),flow=workflow("broken"); var saved=workflows.save(flow,save(workflows.view(flow),List.of(node(flow,sql)),List.of()),"dev");
        workflows.execute(flow,saved.version(),saved.graphHash(),"schedule","dev");
        jdbc.update("UPDATE sql_task SET sql_text='broken sql' WHERE id=?",sql);
        workflows.execute(flow,saved.version(),"stale-hash","pause","dev");
        assertThat(workflows.require(flow).getStatus()).isEqualTo("PAUSED"); verify(engine).offline(91);
    }

    @Test void untouchedUpgradeReusesOnlineDefinitionAndLegacyWorkersUntilTopologyChanges() {
        long sql=task("dwd_upgrade"),flow=workflow("upgrade");
        var view=workflows.save(flow,save(workflows.view(flow),List.of(node(flow,sql)),List.of()),"dev");
        String oldHash=tasks.graph(sql).hash();
        jdbc.update("INSERT INTO workflow_legacy_binding(workflow_id,root_task_id,graph_hash) VALUES(?,?,?)",flow,sql,oldHash);
        jdbc.update("UPDATE processing_workflow SET definition_code=734,definition_hash=?,schedule_id=91,status='SCHEDULED' WHERE id=?",oldHash,flow);
        workflows.execute(flow,view.version(),view.graphHash(),"run","dev");
        verify(engine).start(734L); verify(engine,never()).create(anyString(),anyString(),anyList()); verify(engine,never()).offline(anyInt());
        assertThat(workflows.require(flow).getStatus()).isEqualTo("SCHEDULED");
        assertThat(operations.graph(operations.resource("workflow",flow),734L)).isNull();
        long run=((Number)tasks.claim(sql,1,sql,oldHash).get("runId")).longValue(); tasks.finish(run,true);
        workflows.execute(flow,view.version(),"","pause","dev");
        workflows.save(flow,save(workflows.view(flow),List.of(),List.of()),"dev");
        assertThatThrownBy(()->tasks.claim(sql,1,sql,oldHash)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private long workflow(String name) { var w=new ProcessingWorkflow(); w.setName(name); return workflows.metadata(null,w,"dev"); }
    private WorkflowService.Node node(long w,long id) { return workflows.expand(w,"sql-"+id).getFirst(); }
    private WorkflowService.Save save(WorkflowService.View v,List<WorkflowService.Node> n,List<WorkflowService.Edge> e) { return new WorkflowService.Save(v.version(),v.graphHash(),v.cronExpression(),n,e,Map.of()); }
    private List<WorkflowService.Edge> edges(String... v) { var e=new ArrayList<WorkflowService.Edge>(); for(int i=0;i<v.length;i+=2)e.add(new WorkflowService.Edge(v[i],v[i+1])); return e; }
    private long task(String table) { long m=model(table,table.startsWith("ads")?"ADS":"DWD"); var t=new SqlTask(); t.setName(table);t.setModelId(m);t.setSqlText("INSERT INTO "+table+" SELECT id FROM ods_orders"); return tasks.save(null,t,"dev"); }
    private long model(String table,String layer) { var m=new DataModel();m.setName(table);m.setTableName(table);m.setSubjectArea("测试");m.setLayer(layer);var f=new ModelField();f.setName("id");f.setDataType("BIGINT");f.setKeyColumn(true);m.setFields(List.of(f));return models.save(null,m,"dev"); }
    private long pipeline(String target) { sources.create("source-"+target,MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()); model(target,"ODS"); pipelines.create(target,platform.sources().getFirst().getId(),"orders","updated_at",target,"","0 0 * * * ?"); return platform.pipelines().getFirst().getId(); }
}
