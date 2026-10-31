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
import com.litedata.store.*;
import com.litedata.pipeline.PipelineService;
import com.litedata.pipeline.PipelineTransformMigration;
import com.litedata.transform.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Real metadata/transactions and MVC; only the external Doris and DS boundaries are mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransformIntegrationTest {
    private static final String TOKEN="sql-task-test-internal-token-0123456789";
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("transform-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl); registry.add("spring.datasource.username",MYSQL::getUsername);
        registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("platform.encryption-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token",()->TOKEN); registry.add("platform.admin-password",()->"TransformAdminPassword2026");
        registry.add("platform.doris.jdbc-url",MYSQL::getJdbcUrl); registry.add("platform.doris.username",MYSQL::getUsername);
        registry.add("platform.doris.password",MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransformService tasks;
    @Autowired TransformScheduler scheduler;
    @Autowired ModelingService models;
    @Autowired SqlTaskMapper mapper;
    @Autowired SourceService sources;
    @Autowired PipelineService pipelines;
    @Autowired PipelineTransformMigration migration;
    @Autowired PlatformMapper platform;
    @MockitoBean DorisModelGateway gateway;
    @MockitoBean DolphinWorkflowClient engine;

    @BeforeEach void clean() throws Exception {
        for(String table:List.of("pipeline_transform_archive","sql_task_run","sql_task_dependency","sql_pipeline_dependency","sql_task","model_execution","model_field","data_model","pipeline_run","pipeline","data_source","audit_event")) jdbc.update("DELETE FROM "+table);
        reset(gateway,engine);
        when(gateway.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id","bigint(20)",false,"")),List.of("id"),List.of("id"),1));
        when(engine.configured()).thenReturn(true); when(engine.create(anyString(),anyString(),anyList())).thenReturn(88L);
        when(engine.createSchedule(anyLong(),anyString())).thenReturn(77);
    }

    @Test void createsDiamondGraphDeduplicatesSharedNodesAndRejectsCyclesTransactionally() {
        long a=task("dwd_a",List.of()),b=task("dws_b",List.of(a)),c=task("dws_c",List.of(a)),d=task("ads_d",List.of(b,c));
        var graph=tasks.graph(d);
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes().getFirst().refId()).isEqualTo(a);
        assertThat(graph.nodes().getLast().upstreamKeys()).containsExactly("sql-"+b,"sql-"+c);
        var edit=tasks.require(a); edit.setUpstreamTaskIds(List.of(d));
        assertThatIllegalArgumentException().isThrownBy(()->tasks.save(a,edit,"dev")).withMessageContaining("循环");
        assertThat(tasks.require(a).getVersion()).isEqualTo(1);
        assertThat(tasks.require(a).getUpstreamTaskIds()).isEmpty();
        assertThat(tasks.graph(d).hash()).isEqualTo(graph.hash());
    }

    @Test void pipelineDependencySupportsPureOdsSyncWithoutLegacyTransform() {
        sources.create("sync-source",MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        model("ods_orders","ODS");
        pipelines.create("ods-only",platform.sources().getFirst().getId(),"orders","updated_at","ods_orders","","0 0 * * * ?");
        var pipeline=platform.pipelines().getFirst(); assertThat(pipeline.getTransformSql()).isEmpty();
        long id=task("dwd_orders",List.of()); var edit=tasks.require(id); edit.setUpstreamPipelineIds(List.of(pipeline.getId()));
        tasks.save(id,edit,"dev");
        var graph=tasks.graph(id); assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.nodes().getFirst().kind()).isEqualTo("同步");
        assertThat(graph.nodes().getLast().upstreamKeys()).containsExactly("pipeline-"+pipeline.getId());
    }

    @Test void rejectsNonUniqueTargetsOnSaveAndRechecksAfterModelChanges() {
        long id = task("dws_duplicate_guard", List.of());
        var task = tasks.require(id);
        var model = models.require(task.getModelId());
        model.setModelType("DUPLICATE"); models.save(model.getId(), model, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.save(id, task, "dev")).withMessageContaining("UNIQUE");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.preflight(tasks.graph(id))).withMessageContaining("UNIQUE");
        assertThat(mapper.runs(id)).isEmpty();
        var changed=models.require(task.getModelId()); changed.setModelType("UNIQUE"); changed.setLayer("ODS");
        models.save(changed.getId(),changed,"dev");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.preflight(tasks.graph(id))).withMessageContaining("ODS");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.claim(id,task.getVersion(),id,tasks.graph(id).hash())).withMessageContaining("ODS");
    }

    @Test void dimensionCanBeProcessedBeforeFactAndOfferedAsATarget() throws Exception {
        long dimension = task("dim_enterprise", List.of());
        long fact = task("dwd_usage", List.of(dimension));
        var edit = tasks.require(fact);
        edit.setSqlText("INSERT INTO dwd_usage SELECT u.id FROM ods_orders u JOIN dim_enterprise e ON u.id = e.id");
        tasks.save(fact, edit, "dev");
        var graph = tasks.graph(fact);
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.nodes().getFirst().refId()).isEqualTo(dimension);
        assertThat(graph.nodes().getLast().upstreamKeys()).containsExactly("sql-" + dimension);
        mvc.perform(get("/transforms/new").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("DIM / dim_enterprise")));
        long run = (Long) tasks.claim(dimension, 1, fact, graph.hash()).get("runId");
        tasks.finish(run, true);
        assertThat(mapper.run(run).getStatus()).isEqualTo("SUCCESS");
    }

    @Test void claimsAreExclusiveVersionedAndCompletionIsIdempotentWithoutTouchingPipelineWatermarks() {
        long id=task("dwd_orders",List.of()); String hash=tasks.graph(id).hash();
        var claim=tasks.claim(id,1,id,hash); long runId=(Long)claim.get("runId");
        assertThat(claim).containsEntry("targetTable","dwd_orders").doesNotContainKeys("password","doris","source");
        var busy=catchThrowableOfType(()->tasks.claim(id,1,id,hash),ResponseStatusException.class);
        assertThat(busy.getStatusCode().value()).isEqualTo(423);
        assertThatIllegalArgumentException().isThrownBy(()->tasks.save(id,tasks.require(id),"dev")).withMessageContaining("运行");
        tasks.finish(runId,false); tasks.finish(runId,true);
        assertThat(mapper.run(runId).getStatus()).isEqualTo("FAILED"); assertThat(tasks.require(id).getActiveRunId()).isNull();
        long retry=(Long)tasks.claim(id,1,id,hash).get("runId"); tasks.finish(retry,true); tasks.finish(retry,false);
        assertThat(mapper.run(retry).getStatus()).isEqualTo("SUCCESS");
        assertThat(mapper.run(retry).getSqlText()).contains("INSERT INTO dwd_orders");
        var edit=tasks.require(id); tasks.save(id,edit,"dev");
        assertThatThrownBy(()->tasks.claim(id,1,id,hash)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void publishesOnlyFixedWorkerCommandsAndPersistsScheduleBeforeOnlining() {
        long upstream=task("dwd_orders",List.of()),root=task("ads_orders",List.of(upstream));
        scheduler.run(root,1,"dev");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<DolphinWorkflowClient.Node>> nodes=ArgumentCaptor.forClass(List.class);
        verify(engine).create(anyString(),anyString(),nodes.capture());
        assertThat(nodes.getValue()).hasSize(2);
        assertThat(nodes.getValue().getLast().script()).contains("run_sql_task.py --task-id "+root,"--root-id "+root,"--graph-hash").doesNotContain("INSERT","password");
        var order=inOrder(engine); order.verify(engine).publish(88L); order.verify(engine).start(88L);
        scheduler.schedule(root,1,"dev"); assertThat(tasks.require(root).getStatus()).isEqualTo("SCHEDULED");
        assertThatIllegalArgumentException().isThrownBy(()->tasks.save(upstream,tasks.require(upstream),"dev")).withMessageContaining("停用");
        scheduler.pause(root,1,"dev"); assertThat(tasks.require(root).getStatus()).isEqualTo("PAUSED");
        tasks.save(upstream,tasks.require(upstream),"dev"); scheduler.schedule(root,1,"dev");
        verify(engine,times(2)).create(anyString(),anyString(),anyList()); verify(engine).offline(77);
    }

    @Test void keepsHistoricalWorkflowBindingsForEveryNodeWhenTheChainChanges() {
        long upstream=task("dwd_history",List.of()),root=task("ads_history",List.of(upstream));
        when(engine.create(anyString(),anyString(),anyList())).thenReturn(88L,99L);
        scheduler.run(root,1,"dev");
        var edit=tasks.require(upstream); edit.setName("changed-upstream"); tasks.save(upstream,edit,"dev");
        scheduler.run(root,1,"dev");
        assertThat(platform.workflowCodes("transform",root)).containsExactlyInAnyOrder(88L,99L);
        assertThat(platform.workflowCodes("transform",upstream)).containsExactlyInAnyOrder(88L,99L);
        assertThat(tasks.require(root).getDefinitionCode()).isEqualTo(99L);
    }

    @Test void cannotEditOrArchiveARootWhileItsUpstreamNodeIsRunning() {
        long upstream=task("dwd_orders",List.of()),root=task("ads_orders",List.of(upstream));
        long runId=(Long)tasks.claim(upstream,1,root,tasks.graph(root).hash()).get("runId");
        assertThatIllegalArgumentException().isThrownBy(()->tasks.save(root,tasks.require(root),"dev")).withMessageContaining("运行");
        assertThatIllegalArgumentException().isThrownBy(()->tasks.archive(root,1,"dev")).withMessageContaining("运行");
        tasks.finish(runId,false);
        tasks.save(root,tasks.require(root),"dev");
        assertThat(tasks.require(root).getVersion()).isEqualTo(2);
    }

    @Test void onlineFailureKeepsPendingScheduleForSafeRetry() {
        long id=task("dwd_orders",List.of()); doThrow(new IllegalStateException("engine unavailable")).when(engine).online(77);
        assertThatIllegalStateException().isThrownBy(()->scheduler.schedule(id,1,"dev"));
        assertThat(tasks.require(id).getScheduleId()).isEqualTo(77);
        assertThat(tasks.require(id).getStatus()).isEqualTo("SCHEDULE_PENDING");
        doNothing().when(engine).online(77); scheduler.schedule(id,1,"dev");
        verify(engine,times(1)).createSchedule(88L,"0 0 * * * ?");
        assertThat(tasks.require(id).getStatus()).isEqualTo("SCHEDULED");
    }

    @Test void rendersPagesAndEnforcesRolesCsrfAndInternalToken() throws Exception {
        long id=task("dwd_orders",List.of());
        mvc.perform(get("/transforms")).andExpect(status().is3xxRedirection());
        for(String page:List.of("/transforms","/transforms/new","/transforms/"+id,"/transforms/"+id+"/edit"))
            mvc.perform(get(page).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/transforms/"+id).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("运行整条依赖链</button>"))));
        for(String action:List.of("/run","/schedule","/pause","/archive"))
            mvc.perform(post("/transforms/"+id+action).with(user("viewer").roles("VIEWER")).with(csrf()).param("version","1"))
                    .andExpect(status().isForbidden());
        mvc.perform(post("/transforms/"+id+"/run").with(user("admin").roles("ADMIN")).param("version","1")).andExpect(status().isForbidden());
        String body="{\"version\":1,\"rootId\":"+id+",\"graphHash\":\""+tasks.graph(id).hash()+"\"}";
        mvc.perform(post("/internal/sql-tasks/"+id+"/claim").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/sql-tasks/"+id+"/claim").header("X-Internal-Token",TOKEN).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        long runId=tasks.require(id).getActiveRunId(); tasks.finish(runId,true);
        mvc.perform(get("/transforms/"+id).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SUCCESS")));
    }

    @Test void taskEditorReturnsToItsListOrDetailEntryPointAndPreservesListPaging() throws Exception {
        long id=task("dwd_navigation",List.of()); task("dwd_navigation_later",List.of());
        var before=tasks.require(id);
        String listPath="/transforms?pageNum=2&pageSize=1";

        mvc.perform(get("/transforms").param("pageNum","2").param("pageSize","1").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms/"+id+"/edit?returnTo=list&amp;pageNum=2&amp;pageSize=1\">编辑</a>")));
        mvc.perform(get("/transforms/"+id+"/edit").param("returnTo","list").param("pageNum","2").param("pageSize","1")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("returnTo","list"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath",listPath))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms?pageNum=2&amp;pageSize=1\">取消</a>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"returnTo\" value=\"list\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"pageNum\" value=\"2\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"pageSize\" value=\"1\"")));
        mvc.perform(post("/transforms/"+id).with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("returnTo","list").param("pageNum","2").param("pageSize","1")
                        .param("name",before.getName()).param("modelId",Long.toString(before.getModelId()))
                        .param("sqlText",before.getSqlText()+" ").param("version",Integer.toString(before.getVersion())))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(listPath));

        var saved=tasks.require(id);
        mvc.perform(get("/transforms/"+id).with(user("dev").roles("DEVELOPER")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms/"+id+"/edit?returnTo=detail\">编辑任务</a>")));
        mvc.perform(get("/transforms/"+id+"/edit").param("returnTo","detail").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("returnTo","detail"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath","/transforms/"+id))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms/"+id+"\">取消</a>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"returnTo\" value=\"detail\"")));
        mvc.perform(post("/transforms/"+id).with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("returnTo","detail").param("name",saved.getName()).param("modelId",Long.toString(saved.getModelId()))
                        .param("sqlText",saved.getSqlText()+" ").param("version",Integer.toString(saved.getVersion())))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/transforms/"+id));
    }

    @Test void taskEditorDefaultsUnknownOriginsToTheListAndKeepsNavigationAfterValidationFailure() throws Exception {
        long id=task("dwd_navigation_invalid",List.of());
        var before=tasks.require(id);
        mvc.perform(get("/transforms/"+id+"/edit").param("returnTo","https://untrusted.example").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("returnTo","list"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath","/transforms"));
        mvc.perform(post("/transforms/"+id).with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("returnTo","https://untrusted.example").param("name",before.getName())
                        .param("modelId",Long.toString(before.getModelId())).param("sqlText",before.getSqlText()+" ")
                        .param("version",Integer.toString(before.getVersion())))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/transforms"));

        mvc.perform(post("/transforms/"+id).with(user("dev").roles("DEVELOPER")).with(csrf())
                        .param("returnTo","list").param("pageNum","2").param("pageSize","1")
                        .param("name","kept_navigation_name").param("modelId","not-a-number")
                        .param("sqlText",before.getSqlText()).param("version",Integer.toString(tasks.require(id).getVersion())))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeExists("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath","/transforms?pageNum=2&pageSize=1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("draft",org.hamcrest.Matchers.hasProperty("name",org.hamcrest.Matchers.is("kept_navigation_name"))));
    }

    @Test void rejectsStaleModelsAndProtectsModelsAndTasksInUse() throws Exception {
        long upstream=task("dwd_orders",List.of()),root=task("ads_orders",List.of(upstream));
        long modelId=tasks.require(upstream).getModelId();
        assertThatIllegalArgumentException().isThrownBy(()->models.archive(modelId,1,"dev")).withMessageContaining("加工任务");
        assertThatIllegalArgumentException().isThrownBy(()->tasks.archive(upstream,1,"dev")).withMessageContaining("下游");
        String hash=tasks.graph(root).hash(); var model=models.require(modelId); model.setDescription("changed"); models.save(modelId,model,"dev");
        assertThat(tasks.graph(root).hash()).isNotEqualTo(hash);
        assertThatThrownBy(()->tasks.claim(upstream,1,root,hash)).isInstanceOf(ResponseStatusException.class);
        tasks.archive(root,1,"dev"); tasks.archive(upstream,1,"dev");
        assertThat(mapper.tasks()).isEmpty();
    }

    @Test void migratesLegacySqlRewiresConsumersAndRetainsHistoryIdempotently() throws Exception {
        model("dwd_legacy","DWD");
        long pipeline=legacyPipeline("legacy","  INSERT INTO dwd_legacy SELECT id FROM ods_orders  ");
        long consumer=task("ads_consumer",List.of());
        var edit=tasks.require(consumer); edit.setUpstreamPipelineIds(List.of(pipeline)); tasks.save(consumer,edit,"dev");
        long archived=task("ads_archived",List.of());
        var old=tasks.require(archived); old.setUpstreamPipelineIds(List.of(pipeline)); tasks.save(archived,old,"dev");
        tasks.archive(archived,tasks.require(archived).getVersion(),"dev");
        var run=new PipelineRun(); run.setPipelineId(pipeline);
        run.setLowerBound(java.time.LocalDateTime.of(2026,1,1,0,0)); run.setUpperBound(run.getLowerBound().plusHours(1));
        platform.insertRun(run); platform.activeRun(pipeline,run.getId()); pipelines.finish(run.getId(),true,"");
        var watermark=platform.pipeline(pipeline).getWatermark();
        jdbc.update("UPDATE pipeline SET status='PAUSED',schedule_id=7,definition_code=12345 WHERE id=?",pipeline);
        long replacement=migration.migrate(pipeline,"dev");
        assertThat(migration.migrate(pipeline,"dev")).isEqualTo(replacement);
        assertThat(tasks.require(replacement).getUpstreamPipelineIds()).containsExactly(pipeline);
        assertThat(tasks.require(consumer).getUpstreamPipelineIds()).isEmpty();
        assertThat(tasks.require(consumer).getUpstreamTaskIds()).containsExactly(replacement);
        assertThat(tasks.graph(consumer).nodes()).extracting(TransformService.PlannedNode::key)
                .containsExactly("pipeline-"+pipeline,"sql-"+replacement,"sql-"+consumer);
        assertThat(platform.pipeline(pipeline).getTransformSql()).isEmpty();
        assertThat(platform.pipeline(pipeline).getWatermark()).isEqualTo(watermark);
        assertThat(platform.pipeline(pipeline).getStatus()).isEqualTo("PAUSED");
        assertThat(platform.pipeline(pipeline).getScheduleId()).isNull();
        assertThat(platform.pipeline(pipeline).getDefinitionCode()).isNull();
        assertThat(platform.workflowCodes("pipeline",pipeline)).contains(12345L);
        assertThat(mapper.upstreamPipelines(archived)).containsExactly(pipeline);
        assertThat(platform.run(run.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT sql_text FROM pipeline_transform_archive WHERE pipeline_id=?",String.class,pipeline))
                .isEqualTo("  INSERT INTO dwd_legacy SELECT id FROM ods_orders  ");
        mvc.perform(get("/pipelines/"+pipeline+"/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/transforms/"+replacement)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"transformSql\""))));
    }

    @Test void migrationRequiresPausedIdleChainsAndDoesNotOverwriteConflictingSql() {
        long target=task("dwd_legacy",List.of());
        long pipeline=legacyPipeline("legacy","INSERT INTO dwd_legacy SELECT id + 1 FROM ods_orders");
        jdbc.update("UPDATE pipeline SET schedule_id=7,status='SCHEDULED' WHERE id=?",pipeline);
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("暂停");
        jdbc.update("UPDATE pipeline SET status='PAUSED',active_run_id=9 WHERE id=?",pipeline);
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("正在运行");
        jdbc.update("UPDATE pipeline SET active_run_id=NULL WHERE id=?",pipeline);
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("人工合并");
        assertThat(tasks.require(target).getVersion()).isEqualTo(1);
        assertThat(platform.pipeline(pipeline).getTransformSql()).isNotBlank();
        assertThat(migration.migratedTaskId(pipeline)).isNull();
    }

    @Test void reusesIdenticalSqlButRejectsScheduledConsumersAndCyclesTransactionally() {
        long replacement=task("dwd_legacy",List.of());
        long pipeline=legacyPipeline("legacy","insert into dwd_legacy\n SELECT id  FROM ods_orders");
        jdbc.update("UPDATE sql_task SET status='SCHEDULED',schedule_id=6 WHERE id=?",replacement);
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("停用");
        jdbc.update("UPDATE sql_task SET status='PAUSED' WHERE id=?",replacement);
        long consumer=task("ads_consumer",List.of());
        var edit=tasks.require(consumer); edit.setUpstreamPipelineIds(List.of(pipeline)); tasks.save(consumer,edit,"dev");
        jdbc.update("UPDATE sql_task SET status='SCHEDULED',schedule_id=8 WHERE id=?",consumer);
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("停用");
        jdbc.update("UPDATE sql_task SET status='PAUSED' WHERE id=?",consumer);
        var target=tasks.require(replacement); target.setUpstreamTaskIds(List.of(consumer)); tasks.save(replacement,target,"dev");
        int version=tasks.require(replacement).getVersion();
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("循环");
        assertThat(tasks.require(replacement).getVersion()).isEqualTo(version);
        assertThat(tasks.require(consumer).getUpstreamPipelineIds()).containsExactly(pipeline);
        assertThat(migration.migratedTaskId(pipeline)).isNull();
        target=tasks.require(replacement); target.setUpstreamTaskIds(List.of()); tasks.save(replacement,target,"dev");
        assertThat(migration.migrate(pipeline,"dev")).isEqualTo(replacement);
        assertThat(tasks.list()).hasSize(2);
    }

    @Test void rejectsUnregisteredOdsAndUnsafeLegacySqlWithoutDiscardingIt() {
        long pipeline=legacyPipeline("legacy","INSERT INTO missing_target SELECT id FROM ods_orders");
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("登记");
        jdbc.update("UPDATE pipeline SET transform_sql='INSERT INTO ods_orders SELECT id FROM other' WHERE id=?",pipeline);
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("目标须");
        model("dwd_legacy","DWD");
        jdbc.update("UPDATE pipeline SET transform_sql='INSERT INTO dwd_legacy VALUES(1)' WHERE id=?",pipeline);
        assertThatIllegalArgumentException().isThrownBy(()->migration.migrate(pipeline,"dev")).withMessageContaining("SELECT");
        assertThat(platform.pipeline(pipeline).getTransformSql()).contains("VALUES");
        assertThat(tasks.list()).isEmpty();
    }

    @Test void migrationPagePreservesReadOnlySqlAndRequiresWriteRoleAndCsrf() throws Exception {
        model("dwd_legacy","DWD");
        long pipeline=legacyPipeline("legacy","INSERT INTO dwd_legacy SELECT id FROM ods_orders");
        mvc.perform(get("/pipelines/"+pipeline+"/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("旧版同步后 SQL")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"transformSql\""))));
        String action="/pipelines/"+pipeline+"/migrate-transform";
        mvc.perform(post(action).with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post(action).with(user("dev").roles("DEVELOPER"))).andExpect(status().isForbidden());
        mvc.perform(post(action).with(csrf()).with(user("dev").roles("DEVELOPER")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/transforms/"+migration.migratedTaskId(pipeline)));
        assertThat(platform.pipeline(pipeline).getTransformSql()).isEmpty();
    }

    @Test void independentProcessingFailureDoesNotRollBackCompletedOdsWatermark() {
        long pipeline=legacyPipeline("legacy", "INSERT INTO dwd_legacy SELECT id FROM ods_orders");
        model("dwd_legacy","DWD"); long task=migration.migrate(pipeline,"dev");
        var run=new PipelineRun(); run.setPipelineId(pipeline);
        run.setLowerBound(java.time.LocalDateTime.of(2026,1,1,0,0)); run.setUpperBound(run.getLowerBound().plusHours(1));
        platform.insertRun(run); platform.activeRun(pipeline,run.getId()); pipelines.finish(run.getId(),true,"");
        var watermark=platform.pipeline(pipeline).getWatermark();
        long sqlRun=((Number)tasks.claim(task,tasks.require(task).getVersion(),task,tasks.graph(task).hash()).get("runId")).longValue();
        tasks.finish(sqlRun,false); tasks.finish(sqlRun,true);
        assertThat(platform.pipeline(pipeline).getWatermark()).isEqualTo(watermark);
        assertThat(platform.run(run.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(mapper.run(sqlRun).getStatus()).isEqualTo("FAILED");
    }

    private long legacyPipeline(String name,String sql) {
        sources.create("source-"+name,MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        model("ods_orders","ODS");
        pipelines.create(name,platform.sources().getFirst().getId(),"orders","updated_at","ods_orders","","0 0 * * * ?");
        long id=platform.pipelines().getFirst().getId();
        jdbc.update("UPDATE pipeline SET transform_sql=? WHERE id=?",sql,id);
        return id;
    }

    private long model(String table,String layer) {
        var model=new DataModel(); model.setName(table); model.setTableName(table); model.setSubjectArea("测试域");
        model.setLayer(layer);
        if ("DIM".equals(model.getLayer())) model.setDimensionKeys("id");
        var key=new ModelField(); key.setName("id"); key.setDataType("BIGINT"); key.setKeyColumn(true); model.setFields(List.of(key));
        return models.save(null,model,"dev");
    }

    private long task(String table,List<Long> upstream) {
        long modelId=model(table,table.startsWith("dim")?"DIM":table.startsWith("ads")?"ADS":table.startsWith("dws")?"DWS":"DWD");
        var task=new SqlTask(); task.setName(table); task.setModelId(modelId); task.setSqlText("INSERT INTO "+table+" SELECT id FROM ods_orders"); task.setUpstreamTaskIds(upstream);
        return tasks.save(null,task,"dev");
    }
}
