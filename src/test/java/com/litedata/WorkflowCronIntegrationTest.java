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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cron persistence coverage against the real MVC, Flyway, and MySQL stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkflowCronIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("platform").withUsername("platform").withPassword("workflow-cron-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token", () -> "workflow-cron-test-internal-token-0123456789");
        r.add("platform.admin-password", () -> "WorkflowCronAdminPassword2026");
        r.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        r.add("platform.doris.username", MYSQL::getUsername);
        r.add("platform.doris.password", MYSQL::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WorkflowService workflows;
    @Autowired
    TransformService tasks;
    @Autowired
    ModelingService models;
    @Autowired
    SourceService sources;
    @Autowired
    PipelineService pipelines;
    @Autowired
    PlatformMapper platform;
    @MockitoBean
    DorisModelGateway doris;
    @MockitoBean
    DolphinWorkflowClient engine;
    @MockitoBean
    DolphinMonitorClient monitor;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepare() throws Exception {
        for (String table : List.of("data_ready_delivery", "data_ready_event", "quality_alert", "sql_task_run", "pipeline_transform_archive", "workflow_definition_snapshot", "processing_workflow_snapshot", "processing_workflow_edge", "processing_workflow_node", "sql_task_dependency", "sql_pipeline_dependency", "sql_task", "processing_workflow", "model_execution", "model_field", "data_model", "pipeline_run", "pipeline", "data_source", "audit_event"))
            jdbc.update("DELETE FROM " + table);
        reset(doris, engine, monitor);
        when(doris.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id", "bigint", false, "")), List.of("id"), List.of("id"), 1));
        when(engine.configured()).thenReturn(true);
        when(engine.create(anyString(), anyString(), anyList())).thenReturn(901L);
        when(engine.createSchedule(anyLong(), anyString())).thenReturn(92);
    }

    @Test
    void creationWizardPersistsItsCronBeforeOpeningTheCanvas() throws Exception {
        String html = mvc.perform(get("/workflows/new").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("新建加工任务")
            .containsPattern("(?s)data-wizard-step=\"2\".*name=\"triggerMode\".*name=\"cronExpression\".*</section>");
        mvc.perform(post("/workflows").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("name", "wizard-cron").param("description", "创建时设置执行周期")
                .param("triggerMode", "CRON").param("cronExpression", "  0   30  8 ? * MON-FRI "))
            .andExpect(status().is3xxRedirection());
        long id = jdbc.queryForObject("SELECT id FROM processing_workflow WHERE name='wizard-cron'", Long.class);
        assertThat(workflows.require(id).getCronExpression()).isEqualTo("0 30 8 ? * MON-FRI");
        assertThat(workflows.require(id).getTriggerPipelineId()).isNull();
        assertThat(workflows.view(id).nodes()).isEmpty();
        mvc.perform(get("/workflows/" + id).with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-schedule-cron"))));
        mvc.perform(get("/workflows/" + id + "/graph").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cronExpression").value("0 30 8 ? * MON-FRI"));
        task(id, "ads_wizard_cron");
        var view = workflows.view(id);
        workflows.execute(id, view.version(), view.graphHash(), "schedule", "dev");
        verify(engine).createSchedule(901L, "0 30 8 ? * MON-FRI");
    }

    @Test
    void invalidCreationCronKeepsInputsAndDoesNotInsertAWorkflow() throws Exception {
        for (String invalid : List.of("", "invalid", "1 0 * * * ?", "0 0 0 31 2 ?")) {
            var failed = mvc.perform(post("/workflows").with(user("dev").roles("DEVELOPER")).with(csrf())
                    .param("name", "invalid-wizard").param("description", "保留任务说明")
                    .param("triggerMode", "CRON").param("cronExpression", invalid))
                .andExpect(status().isOk()).andExpect(view().name("workflow-form"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeExists("error")).andReturn();
            var draft = (ProcessingWorkflow) failed.getModelAndView().getModel().get("draft");
            assertThat(draft.getName()).isEqualTo("invalid-wizard");
            assertThat(draft.getDescription()).isEqualTo("保留任务说明");
            assertThat(draft.getCronExpression()).isEqualTo(invalid);
            assertThat(failed.getResponse().getContentAsString()).contains("data-initial-step=\"2\"");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow", Long.class)).isZero();
        mvc.perform(post("/workflows").with(user("viewer").roles("VIEWER")).with(csrf())
                .param("name", "forbidden").param("cronExpression", "0 0 2 * * ?"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/workflows").with(user("dev").roles("DEVELOPER"))
                .param("name", "missing-csrf").param("cronExpression", "0 0 2 * * ?"))
            .andExpect(status().isForbidden());
    }

    @Test
    void metadataCronEditsRequirePauseAndReplaceTheScheduleWithoutChangingTheGraph() throws Exception {
        long id = workflow("metadata-cron"), root = task(id, "ads_metadata_cron");
        var initial = workflows.view(id);
        workflows.execute(id, initial.version(), initial.graphHash(), "schedule", "dev");
        mvc.perform(post("/workflows/" + id + "/metadata").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("name", initial.name()).param("version", Integer.toString(initial.version()))
                .param("triggerMode", "CRON").param("cronExpression", "0 30 8 ? * MON-FRI"))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeExists("error"));
        assertThat(workflows.require(id).getCronExpression()).isEqualTo(initial.cronExpression());
        workflows.execute(id, initial.version(), "", "pause", "dev");
        var paused = workflows.view(id);
        int sqlVersion = tasks.require(root).getVersion();
        mvc.perform(post("/workflows/" + id + "/metadata").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("name", paused.name()).param("version", Integer.toString(paused.version()))
                .param("triggerMode", "CRON").param("cronExpression", "invalid"))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeExists("error"));
        assertThat(workflows.require(id).getVersion()).isEqualTo(paused.version());
        mvc.perform(post("/workflows/" + id + "/metadata").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("name", paused.name()).param("version", Integer.toString(paused.version()))
                .param("triggerMode", "CRON").param("cronExpression", "0 30 8 ? * MON-FRI"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/workflows/" + id));
        var changed = workflows.view(id);
        assertThat(changed.cronExpression()).isEqualTo("0 30 8 ? * MON-FRI");
        assertThat(changed.scheduleId()).isNull();
        assertThat(changed.status()).isEqualTo("DRAFT");
        assertThat(changed.graphHash()).isEqualTo(paused.graphHash());
        assertThat(tasks.require(root).getVersion()).isEqualTo(sqlVersion);
        mvc.perform(post("/workflows/" + id + "/metadata").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("name", paused.name()).param("version", Integer.toString(paused.version()))
                .param("triggerMode", "CRON").param("cronExpression", "0 0 3 * * ?"))
            .andExpect(status().isConflict());
        assertThat(workflows.require(id).getCronExpression()).isEqualTo(changed.cronExpression());
        workflows.execute(id, changed.version(), changed.graphHash(), "schedule", "dev");
        verify(engine).createSchedule(901L, "0 30 8 ? * MON-FRI");
    }

    @Test
    void savesAnySupportedQuartzCronNormalizesItAndSchedulesTheSavedValue() throws Exception {
        long id = workflow("cron"), root = task(id, "ads_cron");
        var view = workflows.view(id);
        for (String cron : List.of("0 */15 * * * ?", "0 0 9 ? * MON-FRI", "0 0 0 L * ?", "0 0 9 ? * MON-FRI 2099")) {
            mvc.perform(post("/workflows/" + id + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(save(view, cron))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cronExpression").value(cron));
            view = workflows.view(id);
        }
        String spaced = "  0   0  9   ?  *  MON-FRI   2099 ";
        int version = tasks.require(root).getVersion();
        mvc.perform(post("/workflows/" + id + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(save(view, spaced))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cronExpression").value("0 0 9 ? * MON-FRI 2099"));
        assertThat(tasks.require(root).getVersion()).isEqualTo(version); // whitespace-only normalization is not a SQL-task change

        view = workflows.view(id);
        mvc.perform(post("/workflows/" + id + "/run").with(user("dev").roles("DEVELOPER")).with(csrf())
            .param("version", Integer.toString(view.version())).param("graphHash", view.graphHash())).andExpect(status().is3xxRedirection());
        view = workflows.view(id);
        mvc.perform(post("/workflows/" + id + "/schedule").with(user("dev").roles("DEVELOPER")).with(csrf())
            .param("version", Integer.toString(view.version())).param("graphHash", view.graphHash())).andExpect(status().is3xxRedirection());
        verify(engine).createSchedule(901L, "0 0 9 ? * MON-FRI 2099");
    }

    @Test
    void legacyPresetsRemainOrdinaryValidCronValuesAndStandaloneSqlEditingKeepsCustomCron() throws Exception {
        long id = workflow("legacy-cron"), root = task(id, "ads_legacy_cron");
        var view = workflows.view(id);
        for (String cron : List.of("0 0 * * * ?", "0 */5 * * * ?", "0 0 2 * * ?")) {
            view = workflows.save(id, save(view, cron), "dev");
            assertThat(view.cronExpression()).isEqualTo(cron);
        }
        var before = tasks.require(root);
        String custom = "0 0 9 ? * MON-FRI";
        view = workflows.save(id, save(view, custom), "dev");
        mvc.perform(post("/transforms/" + root).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("workflowId", Long.toString(id)).param("name", before.getName()).param("modelId", Long.toString(before.getModelId()))
                .param("sqlText", before.getSqlText() + " ").param("version", Integer.toString(tasks.require(root).getVersion())))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/workflows/" + id));
        assertThat(workflows.require(id).getCronExpression()).isEqualTo(custom);
    }

    @Test
    void rejectsInvalidCronWithoutChangingGraphTaskOrWorkflowVersionsAndRequiresPauseBeforeCronChange() throws Exception {
        long id = workflow("invalid-cron"), upstream = task(id, "dwd_cron_upstream"), root = task(id, "ads_cron_invalid");
        var initial = workflows.view(id);
        var nodes = new ArrayList<>(initial.nodes());
        var input = save(initial, "0 */15 * * * ?", nodes, List.of(new WorkflowService.Edge("sql-" + upstream, "sql-" + root)));
        var configured = workflows.save(id, input, "dev");
        int taskVersion = tasks.require(root).getVersion(), workflowVersion = workflows.require(id).getVersion();
        var dependencies = edgesFrom(configured);
        for (String invalid : List.of("", "0 0 * * *", "1 0 * * * ?", "0 0 9 * * *", "0 0 9 ? * ?", "0 0 0 31 2 ?")) {
            var invalidInput = save(configured, invalid);
            mvc.perform(post("/workflows/" + id + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(invalidInput)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
            assertThat(edgesFrom(workflows.view(id))).isEqualTo(dependencies);
            assertThat(tasks.require(root).getVersion()).isEqualTo(taskVersion);
            assertThat(workflows.require(id).getVersion()).isEqualTo(workflowVersion);
        }
        mvc.perform(post("/workflows/" + id + "/schedule").with(user("dev").roles("DEVELOPER")).with(csrf())
            .param("version", Integer.toString(configured.version())).param("graphHash", configured.graphHash())).andExpect(status().is3xxRedirection());
        var scheduled = workflows.view(id);
        mvc.perform(post("/workflows/" + id + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(save(scheduled, "0 0 10 ? * MON-FRI"))))
            .andExpect(status().isBadRequest());
        assertThat(workflows.require(id).getCronExpression()).isEqualTo("0 */15 * * * ?");
        mvc.perform(post("/workflows/" + id + "/pause").with(user("dev").roles("DEVELOPER")).with(csrf())
            .param("version", Integer.toString(scheduled.version())).param("graphHash", scheduled.graphHash())).andExpect(status().is3xxRedirection());
        var paused = workflows.view(id);
        mvc.perform(post("/workflows/" + id + "/graph").with(user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(save(paused, "0 0 10 ? * MON-FRI"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cronExpression").value("0 0 10 ? * MON-FRI"));
    }

    @Test
    void pausedCronChangeCreatesANewScheduleWithoutChangingSqlOrGraph() {
        long id = workflow("new-schedule"), root = task(id, "ads_new_schedule");
        var view = workflows.view(id);
        when(engine.createSchedule(anyLong(), anyString())).thenReturn(92, 93);
        workflows.execute(id, view.version(), view.graphHash(), "schedule", "dev");
        workflows.execute(id, view.version(), "", "pause", "dev");
        var paused = workflows.view(id);
        int sqlVersion = tasks.require(root).getVersion();
        var changed = workflows.save(id, save(paused, "0 30 8 ? * MON-FRI"), "dev");
        assertThat(changed.scheduleId()).isNull();
        assertThat(changed.graphHash()).isEqualTo(paused.graphHash());
        assertThat(tasks.require(root).getVersion()).isEqualTo(sqlVersion);
        workflows.execute(id, changed.version(), changed.graphHash(), "schedule", "dev");
        assertThat(workflows.require(id).getScheduleId()).isEqualTo(93);
        verify(engine).createSchedule(901L, "0 30 8 ? * MON-FRI");
        verify(engine).offline(92);
        verify(engine, times(1)).create(anyString(), anyString(), anyList());
    }

    @Test
    void onlineFailureRetainsPendingScheduleForRetry() {
        long id = workflow("retry");
        task(id, "ads_retry");
        var view = workflows.view(id);
        doThrow(new IllegalStateException("test offline engine")).when(engine).online(92);
        assertThatIllegalStateException().isThrownBy(() -> workflows.execute(id, view.version(), view.graphHash(), "schedule", "dev"));
        assertThat(workflows.require(id).getStatus()).isEqualTo("SCHEDULE_PENDING");
        assertThat(workflows.require(id).getScheduleId()).isEqualTo(92);
        doNothing().when(engine).online(92);
        workflows.execute(id, view.version(), view.graphHash(), "schedule", "dev");
        assertThat(workflows.require(id).getStatus()).isEqualTo("SCHEDULED");
        verify(engine, times(1)).createSchedule(anyLong(), anyString());
    }

    private long workflow(String name) {
        var value = new ProcessingWorkflow();
        value.setName(name);
        return workflows.metadata(null, value, "dev");
    }

    private WorkflowService.Save save(WorkflowService.View view, String cron) {
        return save(view, cron, view.nodes(), edgesFrom(view));
    }

    private WorkflowService.Save save(WorkflowService.View view, String cron, List<WorkflowService.Node> nodes, List<WorkflowService.Edge> edges) {
        return new WorkflowService.Save(view.version(), view.graphHash(), cron, nodes, edges, Map.of());
    }

    private List<WorkflowService.Edge> edgesFrom(WorkflowService.View view) {
        return view.nodes().stream().flatMap(n -> n.upstreamKeys().stream().map(key -> new WorkflowService.Edge(key, n.key()))).toList();
    }

    private long task(long workflow, String table) {
        long model = models.save(null, model(table, table.startsWith("ads") ? "ADS" : "DWD"), "dev");
        var task = new SqlTask();
        task.setWorkflowId(workflow);
        task.setName(table);
        task.setModelId(model);
        task.setSqlText("INSERT INTO " + table + " SELECT id FROM ods_orders");
        return tasks.save(null, task, "dev");
    }

    private DataModel model(String table, String layer) {
        var value = new DataModel();
        value.setName(table);
        value.setTableName(table);
        value.setSubjectArea("测试");
        value.setLayer(layer);
        var field = new ModelField();
        field.setName("id");
        field.setDataType("BIGINT");
        field.setKeyColumn(true);
        value.setFields(List.of(field));
        return value;
    }
}
