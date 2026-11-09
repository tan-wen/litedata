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

/**
 * Real metadata/transactions and MVC; only the external Doris and DS boundaries are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransformIntegrationTest {
    private static final String TOKEN = "sql-task-test-internal-token-0123456789";
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("platform").withUsername("platform").withPassword("transform-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token", () -> TOKEN);
        registry.add("platform.admin-password", () -> "TransformAdminPassword2026");
        registry.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("platform.doris.username", MYSQL::getUsername);
        registry.add("platform.doris.password", MYSQL::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransformService tasks;
    @Autowired
    com.litedata.workflow.WorkflowService workflows;
    @Autowired
    ModelingService models;
    @Autowired
    SqlTaskMapper mapper;
    @Autowired
    SourceService sources;
    @Autowired
    PipelineService pipelines;
    @Autowired
    PlatformMapper platform;
    @MockitoBean
    DorisModelGateway gateway;
    @MockitoBean
    DolphinWorkflowClient engine;

    @BeforeEach
    void clean() throws Exception {
        TestDictionaries.seed(jdbc);
        for (String table : List.of("data_ready_delivery", "data_ready_event", "quality_alert", "pipeline_transform_archive", "sql_task_run", "workflow_definition_snapshot", "processing_workflow_snapshot", "processing_workflow_edge", "processing_workflow_node", "sql_task_dependency", "sql_pipeline_dependency", "sql_task", "processing_workflow", "model_execution", "model_field", "data_model", "pipeline_run", "pipeline", "data_source", "audit_event"))
            jdbc.update("DELETE FROM " + table);
        reset(gateway, engine);
        when(gateway.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id", "bigint(20)", false, "")), List.of("id"), List.of("id"), 1));
        when(engine.configured()).thenReturn(true);
        when(engine.create(anyString(), anyString(), anyList())).thenReturn(88L);
        when(engine.createSchedule(anyLong(), anyString())).thenReturn(77);
    }

    @Test
    void createsDiamondGraphDeduplicatesSharedNodesAndRejectsCyclesTransactionally() {
        long a = task("dwd_a", List.of()), b = task("dws_b", List.of(a)), c = task("dws_c", List.of(a)), d = task("ads_d", List.of(b, c));
        var graph = tasks.graph(d);
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes().getFirst().refId()).isEqualTo(a);
        assertThat(graph.nodes().getLast().upstreamKeys()).containsExactly("sql-" + b, "sql-" + c);
        var edit = tasks.require(a);
        edit.setUpstreamTaskIds(List.of(d));
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.save(a, edit, "dev")).withMessageContaining("循环");
        assertThat(tasks.require(a).getVersion()).isEqualTo(1);
        assertThat(tasks.require(a).getUpstreamTaskIds()).isEmpty();
        assertThat(tasks.graph(d).hash()).isEqualTo(graph.hash());
    }

    @Test
    void rejectsPipelineDependenciesAndKeepsTheStoredSqlUnchanged() {
        long pipeline = legacyPipeline("event-source", "");
        long task = task("dwd_event_target", List.of());
        var before = tasks.require(task);
        var edit = tasks.require(task);
        edit.setUpstreamPipelineIds(List.of(pipeline));
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.save(task, edit, "dev")).withMessageContaining("数据就绪");
        assertThat(tasks.require(task).getVersion()).isEqualTo(before.getVersion());
        assertThat(tasks.require(task).getUpstreamPipelineIds()).isEmpty();
    }

    @Test
    void rejectsNonUniqueTargetsOnSaveAndRechecksAfterModelChanges() {
        long id = task("dws_duplicate_guard", List.of());
        var task = tasks.require(id);
        var model = models.require(task.getModelId());
        model.setModelType("DUPLICATE");
        models.save(model.getId(), model, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.save(id, task, "dev")).withMessageContaining("UNIQUE");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.preflight(tasks.graph(id))).withMessageContaining("UNIQUE");
        assertThat(mapper.runs(id)).isEmpty();
        var changed = models.require(task.getModelId());
        changed.setModelType("UNIQUE");
        changed.setLayer("ODS");
        models.save(changed.getId(), changed, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.preflight(tasks.graph(id))).withMessageContaining("ODS");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.claim(id, task.getVersion(), id, tasks.graph(id).hash())).withMessageContaining("ODS");
    }

    @Test
    void dimensionCanBeProcessedBeforeFactAndOfferedAsATarget() throws Exception {
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

    @Test
    void claimsAreExclusiveVersionedAndCompletionIsIdempotentWithoutTouchingPipelineWatermarks() {
        long id = task("dwd_orders", List.of());
        String hash = tasks.graph(id).hash();
        var claim = tasks.claim(id, 1, id, hash);
        long runId = (Long) claim.get("runId");
        assertThat(claim).containsEntry("targetTable", "dwd_orders").doesNotContainKeys("password", "doris", "source");
        var busy = catchThrowableOfType(() -> tasks.claim(id, 1, id, hash), ResponseStatusException.class);
        assertThat(busy.getStatusCode().value()).isEqualTo(423);
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.save(id, tasks.require(id), "dev")).withMessageContaining("运行");
        tasks.finish(runId, false);
        tasks.finish(runId, true);
        assertThat(mapper.run(runId).getStatus()).isEqualTo("FAILED");
        assertThat(tasks.require(id).getActiveRunId()).isNull();
        long retry = (Long) tasks.claim(id, 1, id, hash).get("runId");
        tasks.finish(retry, true);
        tasks.finish(retry, false);
        assertThat(mapper.run(retry).getStatus()).isEqualTo("SUCCESS");
        assertThat(mapper.run(retry).getSqlText()).contains("INSERT INTO dwd_orders");
        var edit = tasks.require(id);
        tasks.save(id, edit, "dev");
        assertThatThrownBy(() -> tasks.claim(id, 1, id, hash)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cannotEditOrArchiveARootWhileItsUpstreamNodeIsRunning() {
        long upstream = task("dwd_orders", List.of()), root = task("ads_orders", List.of(upstream));
        long runId = (Long) tasks.claim(upstream, 1, root, tasks.graph(root).hash()).get("runId");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.save(root, tasks.require(root), "dev")).withMessageContaining("运行");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.archive(root, 1, "dev")).withMessageContaining("运行");
        tasks.finish(runId, false);
        tasks.save(root, tasks.require(root), "dev");
        assertThat(tasks.require(root).getVersion()).isEqualTo(2);
    }

    @Test
    void rendersPagesAndEnforcesRolesCsrfAndInternalToken() throws Exception {
        long id = task("dwd_orders", List.of());
        mvc.perform(get("/transforms")).andExpect(status().is3xxRedirection());
        for (String page : List.of("/transforms", "/transforms/new", "/transforms/" + id, "/transforms/" + id + "/edit"))
            mvc.perform(get(page).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/transforms/" + id).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("运行整条依赖链</button>"))));
        for (String action : List.of("/run", "/schedule", "/pause", "/archive"))
            mvc.perform(post("/transforms/" + id + action).with(user("viewer").roles("VIEWER")).with(csrf()).param("version", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/transforms/" + id + "/run").with(user("admin").roles("ADMIN")).param("version", "1")).andExpect(status().isForbidden());
        String body = "{\"version\":1,\"rootId\":" + id + ",\"graphHash\":\"" + tasks.graph(id).hash() + "\"}";
        mvc.perform(post("/internal/sql-tasks/" + id + "/claim").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/sql-tasks/" + id + "/claim").header("X-Internal-Token", TOKEN).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        long runId = tasks.require(id).getActiveRunId();
        tasks.finish(runId, true);
        mvc.perform(get("/transforms/" + id).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("SUCCESS")));
    }

    @Test
    void taskEditorReturnsToItsListOrDetailEntryPointAndPreservesListPaging() throws Exception {
        long id = task("dwd_navigation", List.of());
        task("dwd_navigation_later", List.of());
        var before = tasks.require(id);
        String listPath = "/transforms?pageNum=2&pageSize=1";

        mvc.perform(get("/transforms").param("pageNum", "2").param("pageSize", "1").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms/" + id + "/edit?returnTo=list&amp;pageNum=2&amp;pageSize=1\">编辑</a>")));
        mvc.perform(get("/transforms/" + id + "/edit").param("returnTo", "list").param("pageNum", "2").param("pageSize", "1")
                .with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("returnTo", "list"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath", listPath))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms?pageNum=2&amp;pageSize=1\">取消</a>")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"returnTo\" value=\"list\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"pageNum\" value=\"2\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"pageSize\" value=\"1\"")));
        mvc.perform(post("/transforms/" + id).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("returnTo", "list").param("pageNum", "2").param("pageSize", "1")
                .param("name", before.getName()).param("modelId", Long.toString(before.getModelId()))
                .param("sqlText", before.getSqlText() + " ").param("version", Integer.toString(before.getVersion())))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(listPath));

        var saved = tasks.require(id);
        mvc.perform(get("/transforms/" + id).with(user("dev").roles("DEVELOPER")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms/" + id + "/edit?returnTo=detail\">编辑 SQL 任务</a>")));
        mvc.perform(get("/transforms/" + id + "/edit").param("returnTo", "detail").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("returnTo", "detail"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath", "/transforms/" + id))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/transforms/" + id + "\">取消</a>")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"returnTo\" value=\"detail\"")));
        mvc.perform(post("/transforms/" + id).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("returnTo", "detail").param("name", saved.getName()).param("modelId", Long.toString(saved.getModelId()))
                .param("sqlText", saved.getSqlText() + " ").param("version", Integer.toString(saved.getVersion())))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/transforms/" + id));
    }

    @Test
    void taskEditorDefaultsUnknownOriginsToTheListAndKeepsNavigationAfterValidationFailure() throws Exception {
        long id = task("dwd_navigation_invalid", List.of());
        var before = tasks.require(id);
        mvc.perform(get("/transforms/" + id + "/edit").param("returnTo", "https://untrusted.example").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("returnTo", "list"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath", "/transforms"));
        mvc.perform(post("/transforms/" + id).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("returnTo", "https://untrusted.example").param("name", before.getName())
                .param("modelId", Long.toString(before.getModelId())).param("sqlText", before.getSqlText() + " ")
                .param("version", Integer.toString(before.getVersion())))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/transforms"));

        mvc.perform(post("/transforms/" + id).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("returnTo", "list").param("pageNum", "2").param("pageSize", "1")
                .param("name", "kept_navigation_name").param("modelId", "not-a-number")
                .param("sqlText", before.getSqlText()).param("version", Integer.toString(tasks.require(id).getVersion())))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeExists("error"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("backPath", "/transforms?pageNum=2&pageSize=1"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("draft", org.hamcrest.Matchers.hasProperty("name", org.hamcrest.Matchers.is("kept_navigation_name"))));
    }

    @Test
    void rejectsStaleModelsAndProtectsModelsAndTasksInUse() throws Exception {
        long upstream = task("dwd_orders", List.of()), root = task("ads_orders", List.of(upstream));
        long modelId = tasks.require(upstream).getModelId();
        assertThatIllegalArgumentException().isThrownBy(() -> models.archive(modelId, 1, "dev")).withMessageContaining("加工任务");
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.archive(upstream, 1, "dev")).withMessageContaining("工作流引用");
        String hash = tasks.graph(root).hash();
        var model = models.require(modelId);
        model.setDescription("changed");
        models.save(modelId, model, "dev");
        assertThat(tasks.graph(root).hash()).isNotEqualTo(hash);
        assertThatThrownBy(() -> tasks.claim(upstream, 1, root, hash)).isInstanceOf(ResponseStatusException.class);
        workflows.save(tasks.require(root).getWorkflowId(), new com.litedata.workflow.WorkflowService.Save(workflows.view(tasks.require(root).getWorkflowId()).version(), workflows.view(tasks.require(root).getWorkflowId()).graphHash(), workflows.view(tasks.require(root).getWorkflowId()).cronExpression(), List.of(), List.of(), Map.of()), "dev");
        assertThat(mapper.tasks()).isEmpty();
    }

    @Test
    void independentProcessingFailureDoesNotRollBackCompletedOdsWatermark() {
        long pipeline = legacyPipeline("legacy", "INSERT INTO dwd_legacy SELECT id FROM ods_orders");
        model("dwd_legacy", "DWD");
        long task = task("dwd_independent", List.of());
        var run = new PipelineRun();
        run.setPipelineId(pipeline);
        run.setQualityStatus("NOT_REQUIRED");
        run.setLowerBound(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        run.setUpperBound(run.getLowerBound().plusHours(1));
        platform.insertRun(run);
        platform.activeRun(pipeline, run.getId());
        pipelines.finish(run.getId(), true, "");
        var watermark = platform.pipeline(pipeline).getWatermark();
        long sqlRun = ((Number) tasks.claim(task, tasks.require(task).getVersion(), task, tasks.graph(task).hash()).get("runId")).longValue();
        tasks.finish(sqlRun, false);
        tasks.finish(sqlRun, true);
        assertThat(platform.pipeline(pipeline).getWatermark()).isEqualTo(watermark);
        assertThat(platform.run(run.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(mapper.run(sqlRun).getStatus()).isEqualTo("FAILED");
    }

    private long legacyPipeline(String name, String sql) {
        sources.create("source-" + name, MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        model("ods_orders", "ODS");
        pipelines.create(name, platform.sources().getFirst().getId(), "orders", "updated_at", "ods_orders", "", "0 0 * * * ?");
        long id = platform.pipelines().getFirst().getId();
        jdbc.update("UPDATE pipeline SET transform_sql=? WHERE id=?", sql, id);
        return id;
    }

    private long model(String table, String layer) {
        var model = new DataModel();
        model.setName(table);
        model.setTableName(table);
        model.setSubjectArea("测试域");
        model.setLayer(layer);
        if ("DIM".equals(model.getLayer())) model.setDimensionKeys("id");
        var key = new ModelField();
        key.setName("id");
        key.setDataType("BIGINT");
        key.setKeyColumn(true);
        model.setFields(List.of(key));
        return models.save(null, model, "dev");
    }

    private long scheduledWorkflow(long task, int schedule) {
        long id = tasks.require(task).getWorkflowId();
        jdbc.update("UPDATE processing_workflow SET status='SCHEDULED',schedule_id=? WHERE id=?", schedule, id);
        return id;
    }

    private long task(String table, List<Long> upstream) {
        long modelId = model(table, table.startsWith("dim") ? "DIM" : table.startsWith("ads") ? "ADS" : table.startsWith("dws") ? "DWS" : "DWD");
        var task = new SqlTask();
        task.setName(table);
        task.setModelId(modelId);
        task.setSqlText("INSERT INTO " + table + " SELECT id FROM ods_orders");
        task.setUpstreamTaskIds(upstream);
        if (!upstream.isEmpty()) task.setWorkflowId(tasks.require(upstream.getFirst()).getWorkflowId());
        return tasks.save(null, task, "dev");
    }

    private long task(long workflow, String table, List<Long> upstream) {
        long modelId = model(table, table.startsWith("ads") ? "ADS" : "DWD");
        var task = new SqlTask();
        task.setWorkflowId(workflow);
        task.setName(table);
        task.setModelId(modelId);
        task.setSqlText("INSERT INTO " + table + " SELECT id FROM ods_orders");
        task.setUpstreamTaskIds(upstream);
        return tasks.save(null, task, "dev");
    }
}
