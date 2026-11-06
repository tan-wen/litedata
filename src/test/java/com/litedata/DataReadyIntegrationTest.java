package com.litedata;

import static org.assertj.core.api.Assertions.*;

import com.litedata.quality.*;
import com.litedata.model.DataModel;
import com.litedata.model.ModelField;
import com.litedata.modeling.DorisModelGateway;
import com.litedata.modeling.ModelingService;
import com.litedata.store.QualityMapper;
import com.litedata.transform.DolphinWorkflowClient;
import com.litedata.workflow.*;

import java.time.LocalDateTime;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Durable DATA_READY events are recorded per successful pipeline run and only fan out to
 * subscriptions that were enabled when the event was published.
 */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Testcontainers
class DataReadyIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("platform").withUsername("platform").withPassword("ready-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token", () -> "ready-test-internal-token-0123456789");
        r.add("platform.admin-password", () -> "ReadyAdminPassword2026");
        r.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        r.add("platform.doris.username", MYSQL::getUsername);
        r.add("platform.doris.password", MYSQL::getPassword);
        r.add("spring.flyway.clean-disabled", () -> false);
        r.add("platform.readiness.scheduling-enabled", () -> false);
        r.add("platform.quality.scheduling-enabled", () -> false);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Flyway flyway;
    @Autowired
    ReadyEvents ready;
    @Autowired
    WorkflowService workflows;
    @Autowired
    ReadinessDispatcher dispatcher;
    @Autowired
    QualityService quality;
    @Autowired
    QualityMapper qualityRuns;
    @Autowired
    ModelingService models;
    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoBean
    DorisModelGateway doris;
    @MockitoBean
    DolphinWorkflowClient engine;

    @BeforeEach
    void resetDatabase() throws Exception {
        flyway.clean();
        flyway.migrate();
        org.mockito.Mockito.reset(doris, engine);
        org.mockito.Mockito.when(doris.inspect(org.mockito.ArgumentMatchers.anyString())).thenReturn(
            new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id", "BIGINT", false, "")), List.of("id"), List.of("id"), 1));
        org.mockito.Mockito.when(doris.backendCapacity()).thenReturn(new DorisModelGateway.BackendCapacity(1, 1));
        org.mockito.Mockito.when(engine.configured()).thenReturn(true);
        org.mockito.Mockito.when(engine.create(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList())).thenReturn(701L);
    }

    @Test
    void publishesOneReadyEventAndOnlyCurrentSingleSourceSubscribersReceiveIt() {
        long pipeline = pipeline("ods_orders");
        long enabled = eventWorkflow("orders-to-dwd", pipeline, "EVENT_ENABLED");
        long paused = eventWorkflow("orders-paused", pipeline, "PAUSED");
        long cron = eventWorkflow("orders-cron", pipeline, "SCHEDULED");
        long run = successfulRun(pipeline, "NOT_REQUIRED", null);

        ready.publish(run, null);
        ready.publish(run, null); // Lost acknowledgement retry must not duplicate event or submission.

        assertThat(count("data_ready_event")).isEqualTo(1);
        assertThat(count("data_ready_delivery")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT workflow_id FROM data_ready_delivery", Long.class)).isEqualTo(enabled);
        assertThat(jdbc.queryForObject("SELECT trigger_generation FROM data_ready_delivery", Integer.class)).isZero();
        assertThat(List.of(paused, cron)).doesNotContain(jdbc.queryForObject("SELECT workflow_id FROM data_ready_delivery", Long.class));
    }

    @Test
    void paginatedAlertsAndEventsRenderPersistedRecords() throws Exception {
        long pipeline = pipeline("ods_page_ready");
        long workflow = eventWorkflow("page-consumer", pipeline, "EVENT_ENABLED");
        long run = successfulRun(pipeline, "NOT_REQUIRED", null);
        ready.publish(run, null);
        long scan = qualityRun("page-quality", null);
        jdbc.update("INSERT INTO quality_alert(quality_run_id,severity,message) VALUES(?,'WARNING','page-warning')", scan);
        var alerts = quality.alerts(new com.litedata.service.QueryPage(1, 20));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().taskName()).isEqualTo("page-quality");
        assertThat(alerts.getFirst().severity()).isEqualTo("WARNING");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/quality/alerts")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("quality-alerts"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("page-warning")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/workflows/" + workflow + "/events")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("workflow-events"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("PENDING")));
    }

    @Test
    void qualityGateRequiresTheSamePipelineRunToReachPassedOrWarningBeforePublication() {
        long pipeline = pipeline("ods_quality");
        eventWorkflow("quality-consumer", pipeline, "EVENT_ENABLED");

        long blocked = successfulRun(pipeline, "FAILED", null);
        long blockedQuality = qualityRun("quality-blocked", blocked);
        bindQualityEvidence(blocked, blockedQuality, "FAILED");
        assertThatIllegalArgumentException().isThrownBy(() -> ready.publish(blocked, blockedQuality)).withMessageContaining("质量");
        assertThat(count("data_ready_event")).isZero();

        long warning = successfulRun(pipeline, "WARNING", null);
        long warningQuality = qualityRun("quality-warning", warning);
        bindQualityEvidence(warning, warningQuality, "WARNING");
        ready.publish(warning, warningQuality);
        assertThat(jdbc.queryForObject("SELECT quality_run_id FROM data_ready_event WHERE pipeline_run_id=?", Long.class, warning)).isEqualTo(warningQuality);
        assertThat(count("data_ready_delivery")).isEqualTo(1);
    }

    @Test
    void subscriptionCreatedAfterAnEventDoesNotConsumeHistoricalReadiness() {
        long pipeline = pipeline("ods_history");
        long run = successfulRun(pipeline, "NOT_REQUIRED", null);
        ready.publish(run, null);
        eventWorkflow("late-subscriber", pipeline, "EVENT_ENABLED");

        assertThat(count("data_ready_event")).isEqualTo(1);
        assertThat(count("data_ready_delivery")).isZero();
    }

    @Test
    void processingCanvasRejectsDirectPipelineNodes() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowGraph.upstream(List.of("pipeline-1"), List.of()))
            .withMessageContaining("数据就绪订阅");
    }

    @Test
    void eventMetadataRequiresExactlyOnePipelineAndCronCannotCarryOne() {
        long pipeline = pipeline("ods_event_metadata");
        var event = new ProcessingWorkflow();
        event.setName("event metadata");
        event.setDescription("");
        event.setTriggerMode("EVENT");
        event.setTriggerPipelineId(pipeline);
        long saved = workflows.metadata(null, event, "test");
        assertThat(workflows.require(saved).getTriggerMode()).isEqualTo("EVENT");
        assertThat(workflows.require(saved).getTriggerPipelineId()).isEqualTo(pipeline);

        var invalid = new ProcessingWorkflow();
        invalid.setName("invalid cron");
        invalid.setDescription("");
        invalid.setTriggerMode("CRON");
        invalid.setTriggerPipelineId(pipeline);
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.metadata(null, invalid, "test")).withMessageContaining("Cron");
    }

    @Test
    void eventWorkflowSubmitsOnceAndPausedOrRecoveredDeliveriesDoNotStartAgain() {
        long pipeline = pipeline("ods_event_submit");
        long workflow = configuredEventWorkflow("event submit", pipeline);
        long run = successfulRun(pipeline, "NOT_REQUIRED", null);
        ready.publish(run, null);

        dispatcher.tick();
        dispatcher.tick();
        assertThat(jdbc.queryForObject("SELECT state FROM data_ready_delivery WHERE workflow_id=?", String.class, workflow)).isEqualTo("SUBMITTED");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).start(701L);
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).createSchedule(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());

        long pausedRun = successfulRun(pipeline, "NOT_REQUIRED", null);
        ready.publish(pausedRun, null);
        workflows.execute(workflow, workflows.view(workflow).version(), "", "pause", "test");
        dispatcher.tick();
        assertThat(jdbc.queryForObject("SELECT state FROM data_ready_delivery WHERE event_id=(SELECT id FROM data_ready_event WHERE pipeline_run_id=?)", String.class, pausedRun)).isEqualTo("CANCELLED");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).start(701L);

        jdbc.update("UPDATE data_ready_delivery SET state='SUBMITTING' WHERE workflow_id=?", workflow);
        dispatcher.recover();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_ready_delivery WHERE workflow_id=? AND state='UNKNOWN'", Integer.class, workflow)).isPositive();
        dispatcher.tick();
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).start(701L);
    }

    @Test
    void retryOnlyRequeuesFailedDeliveryForItsEnabledSourceAndCurrentGeneration() {
        long pipeline = pipeline("ods_event_retry");
        long workflow = configuredEventWorkflow("event retry", pipeline);
        long run = successfulRun(pipeline, "NOT_REQUIRED", null);
        ready.publish(run, null);
        long delivery = jdbc.queryForObject("SELECT id FROM data_ready_delivery WHERE workflow_id=?", Long.class, workflow);
        jdbc.update("UPDATE data_ready_delivery SET state='FAILED' WHERE id=?", delivery);

        dispatcher.retry(workflow, delivery, "test");
        assertThat(jdbc.queryForObject("SELECT state FROM data_ready_delivery WHERE id=?", String.class, delivery)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT trigger_generation FROM data_ready_delivery WHERE id=?", Integer.class, delivery))
            .isEqualTo(workflows.require(workflow).getTriggerGeneration());
        dispatcher.tick();
        assertThat(jdbc.queryForObject("SELECT state FROM data_ready_delivery WHERE id=?", String.class, delivery)).isEqualTo("SUBMITTED");
        org.mockito.Mockito.verify(engine).start(701L);

        long other = configuredEventWorkflow("event other", pipeline("ods_event_other"));
        jdbc.update("UPDATE data_ready_delivery SET state='FAILED' WHERE id=?", delivery);
        assertThatIllegalArgumentException().isThrownBy(() -> dispatcher.retry(other, delivery, "test")).withMessageContaining("本流程");
        jdbc.update("UPDATE data_ready_delivery SET state='UNKNOWN' WHERE id=?", delivery);
        assertThatIllegalArgumentException().isThrownBy(() -> dispatcher.retry(workflow, delivery, "test")).withMessageContaining("失败事件");
    }

    @Test
    void dispatcherScansTheBoundRunAndStrongFailureBlocksReadiness() {
        jdbc.execute("CREATE TABLE ods_quality_critical(id BIGINT, value_text VARCHAR(10))");
        jdbc.update("INSERT INTO ods_quality_critical VALUES(1,NULL)");
        long pipeline = pipeline("ods_quality_critical");
        long task = qualityTask("critical-gate", "ods_quality_critical", "value_text", "CRITICAL");
        jdbc.update("UPDATE pipeline SET quality_task_id=? WHERE id=?", task, pipeline);
        long run = successfulRun(pipeline, "WAITING", null, task);

        dispatcher.tick();
        awaitQuality(run);

        assertThat(jdbc.queryForObject("SELECT quality_status FROM pipeline_run WHERE id=?", String.class, run)).isEqualTo("FAILED");
        long qualityRun = jdbc.queryForObject("SELECT quality_run_id FROM pipeline_run WHERE id=?", Long.class, run);
        assertThat(qualityRuns.run(qualityRun).getPipelineRunId()).isEqualTo(run);
        assertThat(count("data_ready_event")).isZero();
        assertThat(count("quality_alert")).isEqualTo(1);
    }

    @Test
    void dispatcherAllowsWarningButNeverUsesAnUnrelatedManualQualityRun() {
        jdbc.execute("CREATE TABLE ods_quality_warning(id BIGINT, value_text VARCHAR(10))");
        jdbc.update("INSERT INTO ods_quality_warning VALUES(1,NULL)");
        long pipeline = pipeline("ods_quality_warning");
        long task = qualityTask("warning-gate", "ods_quality_warning", "value_text", "WARNING");
        jdbc.update("UPDATE pipeline SET quality_task_id=? WHERE id=?", task, pipeline);
        long manual = quality.start(task, 1, "manual");
        awaitRun(manual);
        long run = successfulRun(pipeline, "WAITING", null, task);

        dispatcher.tick();
        awaitQuality(run);

        long bound = jdbc.queryForObject("SELECT quality_run_id FROM pipeline_run WHERE id=?", Long.class, run);
        assertThat(bound).isNotEqualTo(manual);
        assertThat(qualityRuns.run(bound).getPipelineRunId()).isEqualTo(run);
        assertThat(jdbc.queryForObject("SELECT quality_status FROM pipeline_run WHERE id=?", String.class, run)).isEqualTo("WARNING");
        assertThat(jdbc.queryForObject("SELECT quality_run_id FROM data_ready_event WHERE pipeline_run_id=?", Long.class, run)).isEqualTo(bound);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quality_alert WHERE quality_run_id=?", Integer.class, bound)).isEqualTo(1);
    }

    @Test
    void dispatcherRecordsScanErrorAndDoesNotPublishReadiness() {
        jdbc.execute("CREATE TABLE ods_quality_error(id BIGINT, value_text VARCHAR(10))");
        long pipeline = pipeline("ods_quality_error");
        long task = qualityTask("error-gate", "ods_quality_error", "missing_column", "CRITICAL");
        jdbc.update("UPDATE pipeline SET quality_task_id=? WHERE id=?", task, pipeline);
        long run = successfulRun(pipeline, "WAITING", null, task);

        dispatcher.tick();
        awaitQuality(run);

        assertThat(jdbc.queryForObject("SELECT quality_status FROM pipeline_run WHERE id=?", String.class, run)).isEqualTo("ERROR");
        assertThat(count("data_ready_event")).isZero();
        assertThat(count("quality_alert")).isEqualTo(1);
    }

    private long pipeline(String target) {
        jdbc.update("INSERT INTO data_source(name,jdbc_url,username,encrypted_password,status) VALUES(?,?,?,?,?)",
            "source-" + target, "jdbc:mysql://unused", "reader", "opaque", "READY");
        long source = jdbc.queryForObject("SELECT id FROM data_source WHERE name=?", Long.class, "source-" + target);
        jdbc.update("INSERT INTO pipeline(name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) VALUES(?,?,?,?,?,?,?)",
            target, source, "orders", "updated_at", target, "", "0 0 * * * ?");
        return jdbc.queryForObject("SELECT id FROM pipeline WHERE name=?", Long.class, target);
    }

    private long eventWorkflow(String name, long pipeline, String status) {
        var workflow = new ProcessingWorkflow();
        workflow.setName(name);
        workflow.setDescription("");
        long id = workflows.metadata(null, workflow, "test");
        jdbc.update("UPDATE processing_workflow SET trigger_mode='EVENT',trigger_pipeline_id=?,status=?,trigger_generation=0 WHERE id=?", pipeline, status, id);
        return id;
    }

    private long configuredEventWorkflow(String name, long pipeline) {
        var draft = new ProcessingWorkflow();
        draft.setName(name);
        draft.setDescription("");
        draft.setTriggerMode("EVENT");
        draft.setTriggerPipelineId(pipeline);
        long workflow = workflows.metadata(null, draft, "test");
        long model = model("dwd_event_" + workflow);
        var blank = workflows.view(workflow);
        var saved = workflows.saveTask(workflow, new WorkflowService.TaskSave(blank.version(), blank.graphHash(), null, 0,
            "event-task-" + workflow, model, "INSERT INTO dwd_event_" + workflow + " SELECT id FROM ods_event"), "test");
        workflows.execute(workflow, saved.version(), saved.graphHash(), "schedule", "test");
        assertThat(workflows.require(workflow).getStatus()).isEqualTo("EVENT_ENABLED");
        assertThatIllegalArgumentException().isThrownBy(() -> workflows.execute(workflow, workflows.view(workflow).version(), workflows.view(workflow).graphHash(), "run", "test"))
            .withMessageContaining("事件模式");
        return workflow;
    }

    private long model(String table) {
        var model = new DataModel();
        model.setName(table);
        model.setTableName(table);
        model.setSubjectArea("测试");
        model.setLayer("DWD");
        model.setModelType("UNIQUE");
        var field = new ModelField();
        field.setName("id");
        field.setDataType("BIGINT");
        field.setKeyColumn(true);
        model.setFields(List.of(field));
        return models.save(null, model, "test");
    }

    private long successfulRun(long pipeline, String qualityStatus, Long qualityRun) {
        return successfulRun(pipeline, qualityStatus, qualityRun, qualityRun == null ? null : 1L);
    }

    private long successfulRun(long pipeline, String qualityStatus, Long qualityRun, Long qualityTask) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO pipeline_run(pipeline_id,status,lower_bound,upper_bound,quality_task_id,quality_status,quality_run_id,finished_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
            pipeline, "SUCCESS", now.minusMinutes(10), now, qualityTask, qualityStatus, qualityRun);
        return jdbc.queryForObject("SELECT MAX(id) FROM pipeline_run WHERE pipeline_id=?", Long.class, pipeline);
    }

    private long qualityTask(String name, String table, String column, String severity) {
        var task = new QualityTask();
        task.setName(name);
        task.setTableName(table);
        task.setOwner("test");
        task.setDescription("");
        task.setCronExpression("");
        task.setTimeColumn("");
        var rule = new QualityRule();
        rule.setName("not-null");
        rule.setType(RuleType.NOT_NULL);
        rule.setColumns(column);
        rule.setParameter1("");
        rule.setParameter2("");
        rule.setReferenceTable("");
        rule.setReferenceColumn("");
        rule.setToleranceCount(0);
        rule.setTolerancePercent(java.math.BigDecimal.ZERO);
        rule.setSeverity(severity);
        task.setRules(List.of(rule));
        return quality.save(null, task, "test");
    }

    private void awaitQuality(long pipelineRun) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject("SELECT quality_status FROM pipeline_run WHERE id=?", String.class, pipelineRun);
            if (!List.of("WAITING", "RUNNING").contains(status)) return;
            pause();
        }
        fail("quality gate did not finish within 10 seconds");
    }

    private void awaitRun(long run) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!"RUNNING".equals(qualityRuns.run(run).getStatus())) return;
            pause();
        }
        fail("quality scan did not finish within 10 seconds");
    }

    private static void pause() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("quality scan interrupted");
        }
    }

    private long qualityRun(String name, Long pipelineRun) {
        jdbc.update("INSERT INTO quality_task(name,table_name,owner,description,cron_expression,time_column,window_hours,timeout_seconds) VALUES(?,?,?,?,?,?,?,?)",
            name, "orders", "test", "", "", "", 24, 30);
        long task = jdbc.queryForObject("SELECT id FROM quality_task WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO quality_run(task_id,task_version,status,actor,config_snapshot,started_at,finished_at,pipeline_run_id) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),?)",
            task, 1, "WARNING", "test", "{}", pipelineRun);
        return jdbc.queryForObject("SELECT MAX(id) FROM quality_run WHERE task_id=?", Long.class, task);
    }

    private void bindQualityEvidence(long pipelineRun, long qualityRun, String status) {
        long task = jdbc.queryForObject("SELECT task_id FROM quality_run WHERE id=?", Long.class, qualityRun);
        jdbc.update("UPDATE pipeline_run SET quality_task_id=?,quality_task_version=1,quality_run_id=?,quality_status=? WHERE id=?",
            task, qualityRun, status, pipelineRun);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
