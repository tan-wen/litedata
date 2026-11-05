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

/**
 * Workflow-local DAG and scheduling ownership backed by real Flyway/MySQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkflowIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("platform").withUsername("platform").withPassword("workflow-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token", () -> "workflow-test-internal-token-0123456789");
        r.add("platform.admin-password", () -> "WorkflowAdminPassword2026");
        r.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        r.add("platform.doris.username", MYSQL::getUsername);
        r.add("platform.doris.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    @Autowired
    WorkflowService workflows;
    @Autowired
    TransformService tasks;
    @Autowired
    SqlTaskMapper sqlTasks;
    @Autowired
    ModelingService models;
    @Autowired
    SourceService sources;
    @Autowired
    PipelineService pipelines;
    @Autowired
    PlatformMapper platform;
    @Autowired
    com.litedata.scheduling.SchedulerOperations operations;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    @MockitoBean
    DorisModelGateway doris;
    @MockitoBean
    DolphinWorkflowClient engine;
    @MockitoBean
    DolphinMonitorClient monitor;

    @BeforeEach
    void prepare() throws Exception {
        for (String table : List.of("sql_task_run", "workflow_definition_snapshot", "processing_workflow_snapshot", "processing_workflow_edge", "processing_workflow_node", "pipeline_transform_archive", "sql_task_dependency", "sql_pipeline_dependency", "sql_task", "processing_workflow", "model_execution", "model_field", "data_model", "pipeline_run", "pipeline", "data_source", "audit_event"))
            jdbc.update("DELETE FROM " + table);
        reset(doris, engine, monitor);
        when(doris.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id", "bigint", false, "")), List.of("id"), List.of("id"), 1));
        when(engine.configured()).thenReturn(true);
        when(engine.create(anyString(), anyString(), anyList())).thenReturn(801L, 802L);
        when(engine.createSchedule(anyLong(), anyString())).thenReturn(91);
    }

    @Test
    void createsEmptyWorkflowThenBuildsIndependentMultiOutputCanvas() {
        long flow = workflow("月末加工");
        var empty = workflows.view(flow);
        assertThat(empty.nodes()).isEmpty();
        long a = task(flow, "dwd_orders"), b = task(flow, "ads_orders"), c = task(flow, "ads_inventory");
        empty = workflows.view(flow);
        var saved = workflows.save(flow, save(empty, List.of(node(flow, a), node(flow, b), node(flow, c)), edges("sql-" + a, "sql-" + b, "sql-" + a, "sql-" + c)), "dev");
        assertThat(saved.nodes()).hasSize(3);
        assertThat(saved.nodes().stream().filter(n -> n.upstreamKeys().isEmpty())).hasSize(1);
        long other = workflow("另一个流程");
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.expand(other, "sql-" + b));
        var reduced = workflows.save(flow, save(saved, List.of(node(flow, a), node(flow, b)), edges("sql-" + a, "sql-" + b)), "dev");
        assertThat(reduced.nodes()).hasSize(2);
        assertThat(sqlTasks.taskIncludingArchived(c).isArchived()).isTrue();
    }

    @Test
    void inlineSqlSaveAndScheduleAreWorkflowOwned() throws Exception {
        long flow = workflow("画布开发");
        var blank = workflows.view(flow);
        long model = model("dwd_inline", "DWD");
        var created = workflows.saveTask(flow, new WorkflowService.TaskSave(blank.version(), blank.graphHash(), null, 0, "画布 SQL", model, "INSERT INTO dwd_inline SELECT id FROM ods_orders"), "dev");
        long id = created.nodes().getFirst().refId();
        assertThat(workflows.task(flow, id).sqlText()).contains("dwd_inline");
        mvc.perform(get("/workflows/" + flow + "/sql-tasks/" + id).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/workflows/" + flow + "/sql-tasks").with(user("viewer").roles("VIEWER")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        workflows.execute(flow, created.version(), created.graphHash(), "schedule", "dev");
        assertThat(workflows.require(flow).getStatus()).isEqualTo("SCHEDULED");
        assertThat(workflows.require(flow).getScheduleId()).isEqualTo(91);
        var scheduled = workflows.view(flow);
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.save(flow, new WorkflowService.Save(scheduled.version(), scheduled.graphHash(), "0 */5 * * * ?", scheduled.nodes(), List.of(), Map.of()), "dev")).withMessageContaining("暂停");
        workflows.execute(flow, workflows.view(flow).version(), "", "pause", "dev");
        assertThat(workflows.require(flow).getStatus()).isEqualTo("PAUSED");
    }

    @Test
    void pipelineStandaloneScheduleIsVisibleButWorkflowUsesItsOwnCron() {
        long pipe = pipeline("ods_orders");
        jdbc.update("UPDATE pipeline SET schedule_id=44,status='SCHEDULED' WHERE id=?", pipe);
        long flow = workflow("含同步"), sql = task(flow, "dwd_orders");
        var view = workflows.view(flow);
        var saved = workflows.save(flow, save(view, List.of(workflows.expand(flow, "pipeline-" + pipe).getFirst(), node(flow, sql)), edges("pipeline-" + pipe, "sql-" + sql)), "dev");
        assertThat(saved.nodes().stream().filter(n -> n.key().equals("pipeline-" + pipe)).findFirst().orElseThrow().standaloneScheduled()).isTrue();
        workflows.execute(flow, saved.version(), saved.graphHash(), "schedule", "dev");
        verify(engine).createSchedule(801L, saved.cronExpression());
    }

    @Test
    void protectsGraphEndpointsWithRolesAndCsrf() throws Exception {
        long flow = workflow("权限");
        String body = "{\"version\":1,\"graphHash\":\"x\",\"cronExpression\":\"0 0 * * * ?\",\"nodes\":[],\"edges\":[]}";
        mvc.perform(get("/workflows/" + flow).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(post("/workflows/" + flow + "/graph").with(user("viewer").roles("VIEWER")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/workflows/" + flow + "/graph").with(user("dev").roles("DEVELOPER")).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }

    @Test
    void rejectsCyclesAndPipelineDownstreamWithoutChangingStoredCanvas() {
        long flow = workflow("图校验"), pipe = pipeline("ods_graph"), a = task(flow, "dwd_graph"), b = task(flow, "ads_graph");
        var base = workflows.view(flow);
        var nodes = List.of(workflows.expand(flow, "pipeline-" + pipe).getFirst(), node(flow, a), node(flow, b));
        var saved = workflows.save(flow, save(base, nodes, edges("pipeline-" + pipe, "sql-" + a, "sql-" + a, "sql-" + b)), "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.save(flow, save(saved, nodes, edges("sql-" + a, "pipeline-" + pipe)), "dev")).withMessageContaining("同步");
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.save(flow, save(saved, nodes, edges("sql-" + a, "sql-" + b, "sql-" + b, "sql-" + a)), "dev")).withMessageContaining("循环");
        assertThat(workflows.view(flow).graphHash()).isEqualTo(saved.graphHash());
    }

    @Test
    void taskPageHasNoStandaloneScheduleActions() throws Exception {
        long id = task("dwd_page");
        mvc.perform(get("/transforms/" + id).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("调度周期"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("启用调度"))));
        for (String action : List.of("run", "schedule", "pause"))
            mvc.perform(post("/transforms/" + id + "/" + action).with(user("dev").roles("DEVELOPER")).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void staleCanvasAndInlineSqlEditsAreRejectedWithoutPartialWrites() throws Exception {
        long flow = workflow("并发");
        long sql = task(flow, "dwd_stale");
        var view = workflows.view(flow);
        var task = workflows.task(flow, sql);
        var input = new WorkflowService.TaskSave(view.version(), view.graphHash(), sql, task.version(), task.name(), task.modelId(), "INSERT INTO dwd_stale SELECT id FROM ods_orders WHERE id>0");
        mvc.perform(post("/workflows/" + flow + "/sql-tasks").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nodes[0].version").value(task.version() + 1));
        mvc.perform(post("/workflows/" + flow + "/sql-tasks").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
            .andExpect(status().isConflict());
        mvc.perform(post("/workflows/" + flow + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(save(view, view.nodes(), List.of()))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty());
        var current = workflows.view(flow);
        int version = tasks.require(sql).getVersion();
        var invalid = new WorkflowService.TaskSave(current.version(), current.graphHash(), sql, version, task.name(), task.modelId(), "DELETE FROM dwd_stale");
        mvc.perform(post("/workflows/" + flow + "/sql-tasks").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(invalid)))
            .andExpect(status().isBadRequest());
        assertThat(tasks.require(sql).getVersion()).isEqualTo(version);
        assertThat(workflows.require(flow).getVersion()).isEqualTo(current.version());
        long foreign = task("ads_foreign");
        mvc.perform(get("/workflows/" + flow + "/sql-tasks/" + foreign).with(user("dev").roles("DEVELOPER"))).andExpect(status().isNotFound());
    }

    @Test
    void snapshotsKeepHistoricalTopologyAndRejectForeignInstances() {
        long flow = workflow("历史图"), a = task(flow, "dwd_history"), b = task(flow, "ads_history");
        var first = workflows.save(flow, save(workflows.view(flow), List.of(node(flow, a), node(flow, b)), edges("sql-" + a, "sql-" + b)), "dev");
        workflows.execute(flow, first.version(), first.graphHash(), "run", "dev");
        var second = workflows.save(flow, save(workflows.view(flow), List.of(node(flow, b)), List.of()), "dev");
        workflows.execute(flow, second.version(), second.graphHash(), "run", "dev");
        var resource = operations.resource("workflow", flow);
        assertThat(resource.codes()).contains(801L, 802L);
        assertThat(operations.graph(resource, 801L)).extracting(WorkflowService.Node::key).containsExactly("sql-" + a, "sql-" + b);
        assertThat(operations.graph(resource, 802L)).extracting(WorkflowService.Node::key).containsExactly("sql-" + b);
        when(monitor.instance(52L)).thenReturn(new com.litedata.scheduling.DolphinMonitorClient.Workflow(52L, 999L, "foreign", "SUCCESS", "", "", 1));
        assertThatThrownBy(() -> operations.instance(resource, 52L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(engine, times(2)).create(anyString(), anyString(), argThat(nodes -> nodes.stream().allMatch(n -> n.script().contains("--workflow-id " + flow) && !n.script().contains("INSERT"))));
    }

    @Test
    void archivesWorkflowWithoutRemovingItsTasksRunsOrSchedulerHistory() throws Exception {
        long flow = workflow("待删除流程"), sql = task(flow, "ads_archive");
        var configured = workflows.view(flow);
        workflows.execute(flow, configured.version(), configured.graphHash(), "run", "dev");
        long run = ((Number) tasks.claim(sql, tasks.require(sql).getVersion(), null, flow, workflows.view(flow).graphHash()).get("runId")).longValue();
        tasks.finish(run, true);

        mvc.perform(post("/workflows/" + flow + "/delete").with(user("dev").roles("DEVELOPER")).param("version", Integer.toString(configured.version())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/workflows/" + flow + "/delete").with(user("viewer").roles("VIEWER")).with(csrf()).param("version", Integer.toString(configured.version())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/workflows/" + flow + "/delete").with(user("dev").roles("DEVELOPER")).with(csrf()).param("version", "0"))
            .andExpect(status().isConflict());

        workflows.execute(flow, workflows.view(flow).version(), workflows.view(flow).graphHash(), "schedule", "dev");
        mvc.perform(post("/workflows/" + flow + "/delete").with(user("dev").roles("DEVELOPER")).with(csrf()).param("version", Integer.toString(workflows.view(flow).version())))
            .andExpect(status().isBadRequest());
        workflows.execute(flow, workflows.view(flow).version(), "", "pause", "dev");
        jdbc.update("UPDATE sql_task SET active_run_id=123 WHERE id=?", sql);
        mvc.perform(post("/workflows/" + flow + "/delete").with(user("dev").roles("DEVELOPER")).with(csrf()).param("version", Integer.toString(workflows.view(flow).version())))
            .andExpect(status().isBadRequest());
        jdbc.update("UPDATE sql_task SET active_run_id=NULL WHERE id=?", sql);

        mvc.perform(post("/workflows/" + flow + "/delete").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("version", Integer.toString(workflows.view(flow).version())).param("keyword", "待删除").param("pageNum", "1").param("pageSize", "20"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/workflows?pageNum=1&pageSize=20&keyword=%E5%BE%85%E5%88%A0%E9%99%A4"));
        assertThat(workflows.count("待删除")).isZero();
        assertThatThrownBy(() -> workflows.require(flow)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(sqlTasks.taskIncludingArchived(sql).isArchived()).isTrue();
        int archivedVersion = sqlTasks.taskIncludingArchived(sql).getVersion();
        assertThatThrownBy(() -> tasks.claim(sql, archivedVersion, null, flow, configured.graphHash()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sql_task_run WHERE id=?", Long.class, run)).isEqualTo(1L);
        var history = operations.resource("workflow", flow);
        assertThat(history.codes()).contains(801L);
        assertThat(operations.graph(history, 801L)).extracting(WorkflowService.Node::key).containsExactly("sql-" + sql);
        mvc.perform(get("/workflows/" + flow).with(user("dev").roles("DEVELOPER"))).andExpect(status().isNotFound());
    }

    @Test
    void sharedSqlIsProtectedByScheduledWorkflowWhileOtherGraphsStayIndependent() {
        long owner = workflow("owner"), shared = task(owner, "dwd_shared"), sink = task(owner, "ads_shared"), other = workflow("other");
        var active = workflows.save(owner, save(workflows.view(owner), List.of(node(owner, shared), node(owner, sink)), edges("sql-" + shared, "sql-" + sink)), "dev");
        workflows.execute(owner, active.version(), active.graphHash(), "schedule", "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.expand(other, "sql-" + shared));
        int version = tasks.require(shared).getVersion();
        var second = workflows.view(other);
        assertThatThrownBy(() -> workflows.saveTask(other, new WorkflowService.TaskSave(second.version(), second.graphHash(), shared, version, "changed", tasks.require(shared).getModelId(), tasks.require(shared).getSqlText()), "dev"));
        var layout = new WorkflowService.Save(active.version(), active.graphHash(), active.cronExpression(), active.nodes(), edges("sql-" + shared, "sql-" + sink), Map.of("sql-" + sink, new WorkflowService.Position(200, 50)));
        var saved = workflows.save(owner, layout, "dev");
        assertThat(saved.graphHash()).isEqualTo(active.graphHash());
        assertThat(tasks.require(shared).getVersion()).isEqualTo(version);
        assertThat(workflows.view(other).nodes()).isEmpty();
        assertThat(workflows.view(owner).nodes()).anyMatch(n -> n.upstreamKeys().contains("sql-" + shared));
    }

    @Test
    void catalogContainsOnlyPipelinesAndDatabaseRejectsForeignSqlMembership() {
        long owner = workflow("私有所有者"), foreign = workflow("另一所有者");
        long sql = task(owner, "dwd_private_membership"), pipe = pipeline("ods_catalog_only");
        var catalog = workflows.catalog(owner, "", new com.litedata.service.QueryPage(1, 20));
        assertThat(catalog.items()).extracting(WorkflowMapper.CatalogItem::kind).containsOnly("同步");
        assertThat(catalog.items()).extracting(WorkflowMapper.CatalogItem::refId).contains(pipe);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO processing_workflow_node(workflow_id,node_key,task_id) VALUES(?,?,?)", foreign, "sql-" + sql, sql))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void ownershipReviewBlocksExecutionSqlEditsAndClaimsUntilAcknowledged() {
        long flow = workflow("迁移核对"), sql = task(flow, "dwd_review");
        jdbc.update("UPDATE processing_workflow SET review_required=TRUE,ownership_notice='核对' WHERE id=?", flow);
        var view = workflows.view(flow);
        var current = tasks.require(sql);
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.execute(flow, view.version(), view.graphHash(), "run", "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.execute(flow, view.version(), view.graphHash(), "schedule", "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.saveTask(flow, new WorkflowService.TaskSave(view.version(), view.graphHash(), sql, current.getVersion(), current.getName(), current.getModelId(), current.getSqlText()), "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> tasks.save(sql, current, "dev"));
        assertThatThrownBy(() -> tasks.claim(sql, current.getVersion(), null, flow, view.graphHash())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(workflows.save(flow, save(view, view.nodes(), List.of()), "dev").reviewRequired()).isTrue();
        var acknowledged = workflows.save(flow, new WorkflowService.Save(view.version() + 1, workflows.view(flow).graphHash(), view.cronExpression(), view.nodes(), List.of(), Map.of(), true), "dev");
        assertThat(acknowledged.reviewRequired()).isFalse();
    }

    @Test
    void removingSqlArchivesDefinitionAndFreesItsNameAndModelForAnotherFlow() {
        long flow = workflow("旧定义"), model = model("ads_reusable", "ADS"), sql = taskWithModel(flow, "reusable", model);
        var before = workflows.view(flow);
        workflows.execute(flow, before.version(), before.graphHash(), "run", "dev");
        var removed = workflows.save(flow, save(workflows.view(flow), List.of(), List.of()), "dev");
        assertThat(sqlTasks.taskIncludingArchived(sql).isArchived()).isTrue();
        assertThat(operations.resource("workflow", flow).codes()).contains(801L);
        long replacement = taskWithModel(workflow("新定义"), "reusable", model);
        assertThat(tasks.require(replacement).getModelId()).isEqualTo(model);
        assertThat(removed.nodes()).isEmpty();
    }

    @Test
    void workflowClaimBindsRunToWorkflowAndRejectsChangedGraph() {
        long flow = workflow("claim"), sql = task(flow, "dwd_claim");
        var view = workflows.view(flow);
        long run = ((Number) tasks.claim(sql, tasks.require(sql).getVersion(), null, flow, view.graphHash()).get("runId")).longValue();
        var mapperRun = jdbc.queryForMap("SELECT workflow_id,root_task_id FROM sql_task_run WHERE id=?", run);
        assertThat(mapperRun).containsEntry("workflow_id", flow).containsEntry("root_task_id", null);
        tasks.finish(run, true);
        int removedVersion = tasks.require(sql).getVersion();
        var removed = workflows.save(flow, save(workflows.view(flow), List.of(), List.of()), "dev");
        assertThatThrownBy(() -> tasks.claim(sql, removedVersion, null, flow, view.graphHash())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.execute(flow, removed.version(), removed.graphHash(), "run", "dev"));
    }

    @Test
    void graphHttpPayloadRemainsCompatibleWhenOptionalMigrationConfirmationIsOmitted() throws Exception {
        long flow = workflow("HTTP compatibility");
        task(flow, "dwd_http_payload");
        var view = workflows.view(flow);
        var payload = Map.of("version", view.version(), "graphHash", view.graphHash(), "cronExpression", view.cronExpression(),
            "nodes", view.nodes(), "edges", List.of(), "positions", Map.of());
        mvc.perform(post("/workflows/" + flow + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(payload)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewRequired").value(false));
    }

    @Test
    void emptyCreationHasNoRootAndPauseWorksEvenForBrokenSql() throws Exception {
        mvc.perform(get("/workflows/new").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("rootTaskId"))));
        mvc.perform(post("/workflows").with(user("dev").roles("DEVELOPER")).with(csrf()).param("name", "empty-form"))
            .andExpect(status().is3xxRedirection());
        long flow = workflow("broken"), sql = task(flow, "dwd_broken");
        var saved = workflows.view(flow);
        workflows.execute(flow, saved.version(), saved.graphHash(), "schedule", "dev");
        jdbc.update("UPDATE sql_task SET sql_text='broken sql' WHERE id=?", sql);
        workflows.execute(flow, saved.version(), "stale-hash", "pause", "dev");
        assertThat(workflows.require(flow).getStatus()).isEqualTo("PAUSED");
        verify(engine).offline(91);
    }

    @Test
    void untouchedUpgradeReusesOnlineDefinitionAndLegacyWorkersUntilTopologyChanges() {
        long flow = workflow("upgrade"), sql = task(flow, "dwd_upgrade");
        var view = workflows.view(flow);
        String oldHash = tasks.graph(sql).hash();
        jdbc.update("INSERT INTO workflow_legacy_binding(workflow_id,root_task_id,graph_hash) VALUES(?,?,?)", flow, sql, oldHash);
        jdbc.update("UPDATE processing_workflow SET definition_code=734,definition_hash=?,schedule_id=91,status='SCHEDULED' WHERE id=?", oldHash, flow);
        workflows.execute(flow, view.version(), view.graphHash(), "run", "dev");
        verify(engine).start(734L);
        verify(engine, never()).create(anyString(), anyString(), anyList());
        verify(engine, never()).offline(anyInt());
        assertThat(workflows.require(flow).getStatus()).isEqualTo("SCHEDULED");
        assertThat(operations.graph(operations.resource("workflow", flow), 734L)).isNull();
        long run = ((Number) tasks.claim(sql, 1, sql, oldHash).get("runId")).longValue();
        tasks.finish(run, true);
        workflows.execute(flow, view.version(), "", "pause", "dev");
        workflows.save(flow, save(workflows.view(flow), List.of(), List.of()), "dev");
        assertThatThrownBy(() -> tasks.claim(sql, 1, sql, oldHash)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void graphSaveCreatesTwoSqlDraftsWithPipelineEdgesPositionsAndCronAtomically() {
        long flow = workflow("统一草稿"), pipe = pipeline("ods_unified"), detail = model("dwd_unified", "DWD"), summary = model("ads_unified", "ADS");
        var blank = workflows.view(flow);
        var saved = workflows.save(flow, save(blank, "0 */5 * * * ?",
            List.of(workflows.expand(flow, "pipeline-" + pipe).getFirst(), draftNode("draft-detail"), draftNode("draft-summary")),
            edges("pipeline-" + pipe, "draft-detail", "draft-detail", "draft-summary"),
            Map.of("pipeline-" + pipe, new WorkflowService.Position(10, 20), "draft-detail", new WorkflowService.Position(30, 40), "draft-summary", new WorkflowService.Position(50, 60)),
            List.of(draft("draft-detail", null, 0, "明细草稿", detail, "INSERT INTO dwd_unified SELECT id FROM ods_unified"),
                draft("draft-summary", null, 0, "汇总草稿", summary, "INSERT INTO ads_unified SELECT id FROM dwd_unified"))), "dev");
        assertThat(saved.version()).isEqualTo(blank.version() + 1);
        assertThat(saved.cronExpression()).isEqualTo("0 */5 * * * ?");
        assertThat(saved.nodes()).extracting(WorkflowService.Node::key).doesNotContain("draft-detail", "draft-summary");
        var sqlNodes = saved.nodes().stream().filter(n -> "SQL".equals(n.kind())).toList();
        assertThat(sqlNodes).hasSize(2);
        assertThat(sqlNodes).allMatch(n -> n.key().matches("sql-\\d+") && n.refId() > 0 && saved.positions().containsKey(n.key()));
        var detailNode = sqlNodes.stream().filter(n -> n.name().equals("明细草稿")).findFirst().orElseThrow();
        var summaryNode = sqlNodes.stream().filter(n -> n.name().equals("汇总草稿")).findFirst().orElseThrow();
        assertThat(summaryNode.upstreamKeys()).containsExactly(detailNode.key());
        assertThat(workflows.task(flow, detailNode.refId()).sqlText()).contains("dwd_unified");
    }

    @Test
    void graphSaveUpdatesExistingSqlAndDependencyInOneVersion() {
        long flow = workflow("同时修改"), a = task(flow, "dwd_together"), b = task(flow, "ads_together");
        var before = workflows.view(flow);
        var current = workflows.task(flow, a);
        var saved = workflows.save(flow, save(before, before.cronExpression(), List.of(node(flow, a), node(flow, b)), edges("sql-" + a, "sql-" + b), Map.of(),
            List.of(draft("sql-" + a, a, current.version(), current.name(), current.modelId(), "INSERT INTO dwd_together SELECT id FROM ods_orders WHERE id>0"))), "dev");
        assertThat(saved.version()).isEqualTo(before.version() + 1);
        assertThat(workflows.task(flow, a).version()).isEqualTo(current.version() + 1);
        assertThat(workflows.task(flow, a).sqlText()).contains("id>0");
        assertThat(saved.nodes().stream().filter(n -> n.key().equals("sql-" + b)).findFirst().orElseThrow().upstreamKeys()).containsExactly("sql-" + a);
    }

    @Test
    void invalidUnifiedDraftRollsBackSqlArchiveGraphCronPositionsAndVersions() {
        long flow = workflow("整体回滚"), a = task(flow, "dwd_rollback"), removed = task(flow, "ads_rollback"), badModel = model("ads_bad_rollback", "ADS");
        var before = workflows.view(flow);
        var old = workflows.task(flow, a);
        var request = save(before, "0 */7 * * * ?", List.of(node(flow, a), draftNode("draft-bad")), edges("sql-" + a, "draft-bad"),
            Map.of("sql-" + a, new WorkflowService.Position(91, 92), "draft-bad", new WorkflowService.Position(93, 94)),
            List.of(draft("sql-" + a, a, old.version(), old.name(), old.modelId(), "INSERT INTO dwd_rollback SELECT id FROM ods_orders WHERE id>0"),
                draft("draft-bad", null, 0, "坏草稿", badModel, "DELETE FROM ads_bad_rollback")));
        assertThatThrownBy(() -> workflows.save(flow, request, "dev")).isInstanceOf(IllegalArgumentException.class);
        assertThat(workflows.task(flow, a).version()).isEqualTo(old.version());
        assertThat(workflows.task(flow, a).sqlText()).isEqualTo(old.sqlText());
        assertThat(sqlTasks.taskIncludingArchived(removed).isArchived()).isFalse();
        var after = workflows.view(flow);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.graphHash()).isEqualTo(before.graphHash());
        assertThat(after.cronExpression()).isEqualTo(before.cronExpression());
        assertThat(after.positions()).isEqualTo(before.positions());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sql_task WHERE name='坏草稿'", Long.class)).isZero();
    }

    @Test
    void unifiedSaveCanReplaceDeletedSqlWithSameNameAndModelAndRejectsForgedDrafts() {
        long flow = workflow("替换定义"), model = model("ads_reusable", "ADS"), old = taskWithModel(flow, "reusable", model);
        var before = workflows.view(flow);
        var replaced = workflows.save(flow, save(before, before.cronExpression(), List.of(draftNode("draft-replacement")), List.of(), Map.of(),
            List.of(draft("draft-replacement", null, 0, "reusable", model, "INSERT INTO ads_reusable SELECT id FROM ods_orders"))), "dev");
        long replacement = replaced.nodes().getFirst().refId();
        assertThat(replacement).isNotEqualTo(old);
        assertThat(sqlTasks.taskIncludingArchived(old).isArchived()).isTrue();
        assertThat(workflows.task(flow, replacement).modelId()).isEqualTo(model);

        long other = workflow("伪造来源"), foreign = task(other, "ads_foreign_draft");
        var unchanged = workflows.view(flow);
        assertThatThrownBy(() -> workflows.save(flow, save(unchanged, unchanged.cronExpression(), List.of(draftNode("draft-forged")), List.of(), Map.of(),
            List.of(draft("draft-forged", foreign, 0, "伪造", model, "INSERT INTO ads_reusable SELECT id FROM ods_orders"))), "dev")).isInstanceOf(IllegalArgumentException.class);
        assertThat(workflows.view(flow).version()).isEqualTo(unchanged.version());
        assertThat(sqlTasks.taskIncludingArchived(foreign).getWorkflowId()).isEqualTo(other);
    }

    @Test
    void unifiedDraftRejectsStaleOrMissingNodeAndScheduledChangesWhileLayoutRemainsAllowed() {
        long flow = workflow("草稿状态"), sql = task(flow, "dwd_draft_state"), newModel = model("ads_draft_state", "ADS");
        var initial = workflows.view(flow);
        var definition = workflows.task(flow, sql);
        var stale = save(initial, initial.cronExpression(), List.of(node(flow, sql), draftNode("draft-stale")), List.of(), Map.of(),
            List.of(draft("draft-stale", null, 0, "stale", newModel, "INSERT INTO ads_draft_state SELECT id FROM ods_orders")));
        workflows.save(flow, save(initial, List.of(node(flow, sql)), List.of()), "dev");
        assertThatThrownBy(() -> workflows.save(flow, stale, "dev")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var current = workflows.view(flow);
        assertThatThrownBy(() -> workflows.save(flow, save(current, current.cronExpression(), current.nodes(), List.of(), Map.of(),
            List.of(draft("draft-orphan", null, 0, "orphan", newModel, "INSERT INTO ads_draft_state SELECT id FROM ods_orders"))), "dev")).isInstanceOf(IllegalArgumentException.class);

        workflows.execute(flow, current.version(), current.graphHash(), "schedule", "dev");
        var scheduled = workflows.view(flow);
        var layout = workflows.save(flow, save(scheduled, scheduled.cronExpression(), scheduled.nodes(), List.of(), Map.of("sql-" + sql, new WorkflowService.Position(11, 12)), List.of()), "dev");
        assertThat(layout.positions()).containsKey("sql-" + sql);
        assertThatThrownBy(() -> workflows.save(flow, save(layout, layout.cronExpression(), layout.nodes(), List.of(), layout.positions(),
            List.of(draft("sql-" + sql, sql, definition.version(), definition.name(), definition.modelId(), "INSERT INTO dwd_draft_state SELECT id FROM ods_orders WHERE id>0"))), "dev"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("暂停");
    }

    @Test
    void databaseUniqueFailureRollsBackStagedSqlArchivesAndTheWholeGraphSave() {
        long flow = workflow("数据库回滚"), changed = task(flow, "dwd_database_rollback"), removed = task(flow, "ads_database_removed");
        long outside = workflow("外部唯一"), taken = task(outside, "ads_database_taken");
        var before = workflows.view(flow);
        var original = workflows.task(flow, changed);
        var takenTask = tasks.require(taken);
        var request = save(before, "0 */9 * * * ?", List.of(node(flow, changed), draftNode("draft-db-conflict")), edges("sql-" + changed, "draft-db-conflict"),
            Map.of("sql-" + changed, new WorkflowService.Position(101, 102), "draft-db-conflict", new WorkflowService.Position(103, 104)),
            List.of(draft("sql-" + changed, changed, original.version(), original.name(), original.modelId(), "INSERT INTO dwd_database_rollback SELECT id FROM ods_orders WHERE id>0"),
                draft("draft-db-conflict", null, 0, "different-db-name", takenTask.getModelId(), "INSERT INTO ads_database_taken SELECT id FROM ods_orders")));
        assertThatThrownBy(() -> workflows.save(flow, request, "dev")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(workflows.task(flow, changed).version()).isEqualTo(original.version());
        assertThat(workflows.task(flow, changed).sqlText()).isEqualTo(original.sqlText());
        assertThat(sqlTasks.taskIncludingArchived(changed).isArchived()).isFalse();
        assertThat(sqlTasks.taskIncludingArchived(removed).isArchived()).isFalse();
        var after = workflows.view(flow);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.graphHash()).isEqualTo(before.graphHash());
        assertThat(after.cronExpression()).isEqualTo(before.cronExpression());
        assertThat(after.positions()).isEqualTo(before.positions());
        assertThat(after.nodes()).extracting(WorkflowService.Node::key).containsExactlyInAnyOrderElementsOf(before.nodes().stream().map(WorkflowService.Node::key).toList());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sql_task WHERE name='different-db-name'", Long.class)).isZero();
    }

    @Test
    void unifiedDraftSaveSwapsTwoExistingSqlNamesAndModelsInOneTransaction() {
        long flow = workflow("互换定义"), first = task(flow, "dwd_swap_first"), second = task(flow, "ads_swap_second");
        var before = workflows.view(flow);
        var a = workflows.task(flow, first);
        var b = workflows.task(flow, second);
        var saved = workflows.save(flow, save(before, before.cronExpression(), List.of(node(flow, first), node(flow, second)), List.of(), Map.of(),
            List.of(draft("sql-" + first, first, a.version(), b.name(), b.modelId(), "INSERT INTO ads_swap_second SELECT id FROM ods_orders"),
                draft("sql-" + second, second, b.version(), a.name(), a.modelId(), "INSERT INTO dwd_swap_first SELECT id FROM ods_orders"))), "dev");
        assertThat(saved.version()).isEqualTo(before.version() + 1);
        assertThat(workflows.task(flow, first)).extracting(WorkflowService.TaskDefinition::name, WorkflowService.TaskDefinition::modelId, WorkflowService.TaskDefinition::version)
            .containsExactly(b.name(), b.modelId(), a.version() + 1);
        assertThat(workflows.task(flow, second)).extracting(WorkflowService.TaskDefinition::name, WorkflowService.TaskDefinition::modelId, WorkflowService.TaskDefinition::version)
            .containsExactly(a.name(), a.modelId(), b.version() + 1);
        assertThat(sqlTasks.taskIncludingArchived(first).isArchived()).isFalse();
        assertThat(sqlTasks.taskIncludingArchived(second).isArchived()).isFalse();
    }

    @Test
    void malformedDraftGraphAndActiveRunRejectWithoutAnyMutation() {
        long flow = workflow("草稿边界"), existing = task(flow, "dwd_draft_boundary"), left = model("dwd_draft_cycle", "DWD"), right = model("ads_draft_cycle", "ADS");
        var before = workflows.view(flow);
        var current = workflows.task(flow, existing);
        var cycle = save(before, before.cronExpression(), List.of(node(flow, existing), draftNode("draft-left"), draftNode("draft-right")),
            edges("draft-left", "draft-right", "draft-right", "draft-left"), Map.of(),
            List.of(draft("draft-left", null, 0, "left", left, "INSERT INTO dwd_draft_cycle SELECT id FROM ods_orders"), draft("draft-right", null, 0, "right", right, "INSERT INTO ads_draft_cycle SELECT id FROM ods_orders")));
        assertThatThrownBy(() -> workflows.save(flow, cycle, "dev")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("循环");
        var invalidPosition = save(before, before.cronExpression(), List.of(node(flow, existing), draftNode("draft-position")), List.of(), Map.of("draft-position", new WorkflowService.Position(-1, 0)),
            List.of(draft("draft-position", null, 0, "position", right, "INSERT INTO ads_draft_cycle SELECT id FROM ods_orders")));
        assertThatThrownBy(() -> workflows.save(flow, invalidPosition, "dev")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("位置");
        var wrongTask = save(before, before.cronExpression(), List.of(node(flow, existing)), List.of(), Map.of(),
            List.of(draft("sql-" + existing, existing + 999, current.version(), current.name(), current.modelId(), current.sqlText())));
        assertThatThrownBy(() -> workflows.save(flow, wrongTask, "dev")).isInstanceOf(IllegalArgumentException.class);
        var wrongVersion = save(before, before.cronExpression(), List.of(node(flow, existing)), List.of(), Map.of(),
            List.of(draft("sql-" + existing, existing, current.version() + 1, current.name(), current.modelId(), current.sqlText())));
        assertThatThrownBy(() -> workflows.save(flow, wrongVersion, "dev")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(workflows.view(flow).version()).isEqualTo(before.version());
        assertThat(tasks.require(existing).getVersion()).isEqualTo(current.version());

        long run = ((Number) tasks.claim(existing, current.version(), null, flow, before.graphHash()).get("runId")).longValue();
        var active = workflows.view(flow);
        assertThatThrownBy(() -> workflows.save(flow, save(active, active.cronExpression(), active.nodes(), List.of(), Map.of(),
            List.of(draft("sql-" + existing, existing, current.version(), current.name(), current.modelId(), "INSERT INTO dwd_draft_boundary SELECT id FROM ods_orders WHERE id>0"))), "dev"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("运行中");
        tasks.finish(run, true);
        assertThat(tasks.require(existing).getVersion()).isEqualTo(current.version());
    }

    @Test
    void graphHttpPayloadAcceptsBrowserSqlDraftsAndReturnsPersistedKeys() throws Exception {
        long flow = workflow("浏览器统一草稿"), model = model("dwd_http_draft", "DWD");
        var initial = workflows.view(flow);
        var browserNode = Map.of("key", "draft-browser", "name", "浏览器 SQL", "kind", "SQL", "refId", 0, "version", 0,
            "standaloneScheduled", false, "tableName", "dwd_http_draft", "upstreamKeys", List.of());
        var body = Map.of("version", initial.version(), "graphHash", initial.graphHash(), "cronExpression", "0 */7 * * * ?",
            "nodes", List.of(browserNode), "edges", List.of(), "positions", Map.of("draft-browser", Map.of("x", 35, "y", 75)),
            "sqlDrafts", List.of(draft("draft-browser", null, 0, "浏览器 SQL", model, "INSERT INTO dwd_http_draft SELECT id FROM ods_orders")));
        var response = mvc.perform(post("/workflows/" + flow + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(initial.version() + 1))
            .andExpect(jsonPath("$.nodes.length()").value(1)).andReturn().getResponse();
        var saved = json.readValue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8), WorkflowService.View.class);
        var node = saved.nodes().getFirst();
        assertThat(node.key()).startsWith("sql-");
        assertThat(saved.positions()).containsEntry(node.key(), new WorkflowService.Position(35, 75));
        assertThat(workflows.task(flow, node.refId()).modelId()).isEqualTo(model);
    }

    @Test
    void sqlModelChoicesExposeOrderedColumnsAndOnlyActiveUniqueNonOdsModels() throws Exception {
        long flow = workflow("模型字段目录");
        long eligible = model("dwd_choice_columns", "DWD", "UNIQUE", List.of("id", "select", "updated_at"));
        var writer = new SqlTask();
        writer.setWorkflowId(flow);
        writer.setName("占用字段模型");
        writer.setModelId(eligible);
        writer.setSqlText("INSERT INTO dwd_choice_columns SELECT id,id,id FROM ods_orders");
        long writerId = tasks.save(null, writer, "dev");
        long ods = model("ods_choice_columns", "ODS", "UNIQUE", List.of("id"));
        long duplicate = model("dwd_duplicate_columns", "DWD", "DUPLICATE", List.of("id"));
        long archived = model("ads_archived_columns", "ADS", "UNIQUE", List.of("id"));
        models.archive(archived, models.require(archived).getVersion(), "dev");

        var response = mvc.perform(get("/workflows/" + flow + "/sql-models").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk()).andReturn().getResponse();
        var choices = json.readValue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8), WorkflowService.ModelChoice[].class);
        var choice = Arrays.stream(choices).filter(item -> item.id() == eligible).findFirst().orElseThrow();
        assertThat(choice.columns()).containsExactly("id", "select", "updated_at");
        assertThat(choice.taskId()).isEqualTo(writerId);
        assertThat(Arrays.stream(choices).map(WorkflowService.ModelChoice::id)).doesNotContain(ods, duplicate, archived);
    }

    @Test
    void workflowListUsesHistoricalSqlAndVerifiedPipelineRunsWithoutGuessingSharedOwnership() throws Exception {
        long flow = workflow("最近执行"), sql = task(flow, "dwd_recent"), pipe = pipeline("ods_recent"), empty = workflow("尚未执行");
        jdbc.update("INSERT INTO sql_task_run(task_id,workflow_id,task_version,target_table,sql_text,status,started_at) VALUES(?,?,1,'dwd_recent','fixture','FAILED','2026-11-04 01:02:03')", sql, flow);
        // A newer id with an older timestamp must not replace the latest start.
        jdbc.update("INSERT INTO sql_task_run(task_id,workflow_id,task_version,target_table,sql_text,started_at) VALUES(?,?,1,'dwd_recent','fixture','2026-11-03 01:02:03')", sql, flow);
        assertThat(workflows.list("最近执行", new com.litedata.service.QueryPage(1, 20)).getFirst().getLastRunAt())
            .isEqualTo(java.time.LocalDateTime.of(2026, 11, 4, 1, 2, 3));
        platform.rememberWorkflow("pipeline", pipe, 808L);
        jdbc.update("INSERT INTO workflow_definition_snapshot(workflow_id,definition_code,nodes_json) VALUES(?,808,'[]')", flow);
        jdbc.update("INSERT INTO pipeline_run(pipeline_id,definition_code,status,started_at,lower_bound,upper_bound) VALUES(?,808,'FAILED','2026-11-04 20:03:04','2026-11-01','2026-11-02')", pipe);
        // Sharing this pipeline in today's canvas is not evidence that a past run belongs to this flow.
        jdbc.update("INSERT INTO processing_workflow_node(workflow_id,node_key,pipeline_id) VALUES(?,?,?)", empty, "pipeline-" + pipe, pipe);
        jdbc.update("INSERT INTO pipeline_run(pipeline_id,definition_code,status,started_at,lower_bound,upper_bound) VALUES(?,999,'SUCCESS','2026-11-05 00:00:00','2026-11-01','2026-11-02')", pipe);
        var listed = workflows.list("", new com.litedata.service.QueryPage(1, 20));
        assertThat(listed.stream().filter(w -> w.getId() == flow).findFirst().orElseThrow().getLastRunAt()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 4, 20, 3, 4));
        assertThat(listed.stream().filter(w -> w.getId() == empty).findFirst().orElseThrow().getLastRunAt()).isNull();
        mvc.perform(get("/workflows").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("最近运行时间")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-11-05 04:03:04")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("运行实例"))));
        verifyNoInteractions(monitor);
    }

    private long workflow(String name) {
        var w = new ProcessingWorkflow();
        w.setName(name);
        return workflows.metadata(null, w, "dev");
    }

    private WorkflowService.Node node(long w, long id) {
        return workflows.expand(w, "sql-" + id).getFirst();
    }

    private WorkflowService.Save save(WorkflowService.View v, List<WorkflowService.Node> n, List<WorkflowService.Edge> e) {
        return new WorkflowService.Save(v.version(), v.graphHash(), v.cronExpression(), n, e, Map.of());
    }

    private WorkflowService.Save save(WorkflowService.View v, String cron, List<WorkflowService.Node> nodes, List<WorkflowService.Edge> edges, Map<String, WorkflowService.Position> positions, List<WorkflowService.SqlDraft> drafts) {
        return new WorkflowService.Save(v.version(), v.graphHash(), cron, nodes, edges, positions, false, drafts);
    }

    private WorkflowService.Node draftNode(String key) {
        return new WorkflowService.Node(key, key, "SQL", 0, 0, List.of(), "", null, "", null, false);
    }

    private WorkflowService.SqlDraft draft(String key, Long taskId, int version, String name, Long model, String sql) {
        return new WorkflowService.SqlDraft(key, taskId, version, name, model, sql);
    }

    private List<WorkflowService.Edge> edges(String... v) {
        var e = new ArrayList<WorkflowService.Edge>();
        for (int i = 0; i < v.length; i += 2) e.add(new WorkflowService.Edge(v[i], v[i + 1]));
        return e;
    }

    private long task(String table) {
        long m = model(table, table.startsWith("ads") ? "ADS" : "DWD");
        var t = new SqlTask();
        t.setName(table);
        t.setModelId(m);
        t.setSqlText("INSERT INTO " + table + " SELECT id FROM ods_orders");
        return tasks.save(null, t, "dev");
    }

    private long task(long workflow, String table) {
        long m = model(table, table.startsWith("ads") ? "ADS" : "DWD");
        var t = new SqlTask();
        t.setWorkflowId(workflow);
        t.setName(table);
        t.setModelId(m);
        t.setSqlText("INSERT INTO " + table + " SELECT id FROM ods_orders");
        return tasks.save(null, t, "dev");
    }

    private long taskWithModel(long workflow, String name, long model) {
        var t = new SqlTask();
        t.setWorkflowId(workflow);
        t.setName(name);
        t.setModelId(model);
        t.setSqlText("INSERT INTO ads_reusable SELECT id FROM ods_orders");
        return tasks.save(null, t, "dev");
    }

    private long model(String table, String layer) {
        return model(table, layer, "UNIQUE", List.of("id"));
    }

    private long model(String table, String layer, String modelType, List<String> fields) {
        var m = new DataModel();
        m.setName(table);
        m.setTableName(table);
        m.setSubjectArea("测试");
        m.setLayer(layer);
        m.setModelType(modelType);
        var definitions = new ArrayList<ModelField>();
        for (int i = 0; i < fields.size(); i++) {
            var f = new ModelField();
            f.setName(fields.get(i));
            f.setDataType("BIGINT");
            f.setKeyColumn(i == 0);
            definitions.add(f);
        }
        m.setFields(definitions);
        return models.save(null, m, "dev");
    }

    private long pipeline(String target) {
        sources.create("source-" + target, MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        model(target, "ODS");
        pipelines.create(target, platform.sources().getFirst().getId(), "orders", "updated_at", target, "", "0 0 * * * ?");
        return platform.pipelines().getFirst().getId();
    }
}
