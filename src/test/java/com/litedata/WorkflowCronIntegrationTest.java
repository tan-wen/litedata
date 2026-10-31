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
import com.litedata.pipeline.PipelineTransformMigration;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Cron persistence coverage against the real MVC, Flyway, and MySQL stack. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkflowCronIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("workflow-cron-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername); r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("platform.encryption-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token",()->"workflow-cron-test-internal-token-0123456789"); r.add("platform.admin-password",()->"WorkflowCronAdminPassword2026");
        r.add("platform.doris.jdbc-url",MYSQL::getJdbcUrl); r.add("platform.doris.username",MYSQL::getUsername); r.add("platform.doris.password",MYSQL::getPassword);
    }

    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired WorkflowService workflows; @Autowired TransformService tasks;
    @Autowired ModelingService models; @Autowired SourceService sources; @Autowired PipelineService pipelines; @Autowired PlatformMapper platform;
    @Autowired PipelineTransformMigration migration;
    @MockitoBean DorisModelGateway doris; @MockitoBean DolphinWorkflowClient engine; @MockitoBean DolphinMonitorClient monitor;
    private final JsonMapper json=JsonMapper.builder().build();

    @BeforeEach void prepare() throws Exception {
        for(String table:List.of("pipeline_transform_archive","processing_workflow_snapshot","processing_workflow","sql_task_run","sql_task_dependency","sql_pipeline_dependency","sql_task","model_execution","model_field","data_model","pipeline_run","pipeline","data_source","audit_event")) jdbc.update("DELETE FROM "+table);
        reset(doris,engine,monitor);
        when(doris.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id","bigint",false,"")),List.of("id"),List.of("id"),1));
        when(engine.configured()).thenReturn(true); when(engine.create(anyString(),anyString(),anyList())).thenReturn(901L); when(engine.createSchedule(anyLong(),anyString())).thenReturn(92);
    }

    @Test void savesAnySupportedQuartzCronNormalizesItAndSchedulesTheSavedValue() throws Exception {
        long root=task("ads_cron"); long id=workflow("cron",root); var view=workflows.view(id);
        for(String cron:List.of("0 */15 * * * ?","0 0 9 ? * MON-FRI","0 0 0 L * ?","0 0 9 ? * MON-FRI 2099")) {
            mvc.perform(post("/workflows/"+id+"/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(save(view,cron))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.cronExpression").value(cron));
            view=workflows.view(id);
        }
        String spaced="  0   0  9   ?  *  MON-FRI   2099 "; int version=tasks.require(root).getVersion();
        mvc.perform(post("/workflows/"+id+"/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(save(view,spaced))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cronExpression").value("0 0 9 ? * MON-FRI 2099"));
        assertThat(tasks.require(root).getVersion()).isEqualTo(version); // whitespace-only normalization is not a SQL-task change

        view=workflows.view(id);
        mvc.perform(post("/workflows/"+id+"/run").with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("version",Integer.toString(view.version())).param("graphHash",view.graphHash())).andExpect(status().is3xxRedirection());
        view=workflows.view(id);
        mvc.perform(post("/workflows/"+id+"/schedule").with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("version",Integer.toString(view.version())).param("graphHash",view.graphHash())).andExpect(status().is3xxRedirection());
        verify(engine).createSchedule(901L,"0 0 9 ? * MON-FRI 2099");
    }

    @Test void legacyPresetsRemainOrdinaryValidCronValuesAndStandaloneSqlEditingKeepsCustomCron() throws Exception {
        long root=task("ads_legacy_cron"); long id=workflow("legacy-cron",root); var view=workflows.view(id);
        for(String cron:List.of("0 0 * * * ?","0 */5 * * * ?","0 0 2 * * ?")) {
            view=workflows.save(id,save(view,cron),"dev");
            assertThat(view.cronExpression()).isEqualTo(cron);
        }
        var before=tasks.require(root); String custom="0 0 9 ? * MON-FRI";
        view=workflows.save(id,save(view,custom),"dev");
        mvc.perform(post("/transforms/"+root).with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("workflowId",Long.toString(id)).param("name",before.getName()).param("modelId",Long.toString(before.getModelId()))
                        .param("sqlText",before.getSqlText()+" ").param("version",Integer.toString(tasks.require(root).getVersion())))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/workflows/"+id));
        assertThat(tasks.require(root).getCronExpression()).isEqualTo(custom);
    }

    @Test void legacyPipelineMigrationPreservesItsCustomCron() {
        sources.create("migration-source",MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        models.save(null,model("ods_migration","ODS"),"dev");
        models.save(null,model("ads_migration","ADS"),"dev");
        String cron="0 15 8 ? * MON-FRI";
        long pipeline=pipelines.create("migration",platform.sources().getFirst().getId(),"orders","updated_at","ods_migration","",cron);
        jdbc.update("UPDATE pipeline SET transform_sql=? WHERE id=?","INSERT INTO ads_migration SELECT id FROM ods_migration",pipeline);
        long task=migration.migrate(pipeline,"dev");
        assertThat(tasks.require(task).getCronExpression()).isEqualTo(cron);
    }

    @Test void rejectsInvalidCronWithoutChangingGraphTaskOrWorkflowVersionsAndRequiresPauseBeforeCronChange() throws Exception {
        long upstream=task("dwd_cron_upstream"),root=task("ads_cron_invalid"); long id=workflow("invalid-cron",root);
        var initial=workflows.view(id); var nodes=new ArrayList<>(initial.nodes()); nodes.addAll(workflows.expand(id,"sql-"+upstream));
        var input=save(initial,"0 */15 * * * ?",nodes,List.of(new WorkflowService.Edge("sql-"+upstream,"sql-"+root)));
        var configured=workflows.save(id,input,"dev");
        int taskVersion=tasks.require(root).getVersion(), workflowVersion=workflows.require(id).getVersion();
        List<Long> dependencies=tasks.require(root).getUpstreamTaskIds();
        for(String invalid:List.of("","0 0 * * *","1 0 * * * ?","0 0 9 * * *","0 0 9 ? * ?","0 0 0 31 2 ?")) {
            var invalidInput=save(configured,invalid);
            mvc.perform(post("/workflows/"+id+"/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(invalidInput)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
            assertThat(tasks.require(root).getUpstreamTaskIds()).isEqualTo(dependencies);
            assertThat(tasks.require(root).getVersion()).isEqualTo(taskVersion);
            assertThat(workflows.require(id).getVersion()).isEqualTo(workflowVersion);
        }
        mvc.perform(post("/workflows/"+id+"/schedule").with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("version",Integer.toString(configured.version())).param("graphHash",configured.graphHash())).andExpect(status().is3xxRedirection());
        var scheduled=workflows.view(id);
        mvc.perform(post("/workflows/"+id+"/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(save(scheduled,"0 0 10 ? * MON-FRI"))))
                .andExpect(status().isBadRequest());
        assertThat(tasks.require(root).getCronExpression()).isEqualTo("0 */15 * * * ?");
        mvc.perform(post("/workflows/"+id+"/pause").with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("version",Integer.toString(scheduled.version())).param("graphHash",scheduled.graphHash())).andExpect(status().is3xxRedirection());
        var paused=workflows.view(id);
        mvc.perform(post("/workflows/"+id+"/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(save(paused,"0 0 10 ? * MON-FRI"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cronExpression").value("0 0 10 ? * MON-FRI"));
    }

    private long workflow(String name,long root) { var value=new ProcessingWorkflow(); value.setName(name); value.setRootTaskId(root); return workflows.metadata(null,value,"dev"); }
    private WorkflowService.Save save(WorkflowService.View view,String cron) { return save(view,cron,view.nodes(),edgesFrom(view)); }
    private WorkflowService.Save save(WorkflowService.View view,String cron,List<WorkflowService.Node> nodes,List<WorkflowService.Edge> edges) { return new WorkflowService.Save(view.version(),view.graphHash(),cron,nodes,edges,Map.of()); }
    private List<WorkflowService.Edge> edgesFrom(WorkflowService.View view) { return view.nodes().stream().flatMap(n->n.upstreamKeys().stream().map(key->new WorkflowService.Edge(key,n.key()))).toList(); }
    private long task(String table) { long model=models.save(null,model(table,table.startsWith("ads")?"ADS":"DWD"),"dev"); var task=new SqlTask(); task.setName(table); task.setModelId(model); task.setSqlText("INSERT INTO "+table+" SELECT id FROM ods_orders"); return tasks.save(null,task,"dev"); }
    private DataModel model(String table,String layer) { var value=new DataModel(); value.setName(table); value.setTableName(table); value.setSubjectArea("测试"); value.setLayer(layer); var field=new ModelField(); field.setName("id"); field.setDataType("BIGINT"); field.setKeyColumn(true); value.setFields(List.of(field)); return value; }
}
