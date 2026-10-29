package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.data.SourceService;
import com.litedata.model.DataModel;
import com.litedata.modeling.DorisModelGateway;
import com.litedata.modeling.ModelingService;
import com.litedata.pipeline.OdsPipelineService;
import com.litedata.pipeline.PipelineService;
import com.litedata.store.ModelMapper;
import com.litedata.store.PlatformMapper;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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

/** ODS onboarding uses a real source JDBC connection and transaction, with only Doris mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OdsPipelineIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("ods-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token", () -> "ods-integration-internal-token-0123456789");
        registry.add("platform.admin-password", () -> "OdsAdminPassword2026");
        registry.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl); registry.add("platform.doris.username", MYSQL::getUsername);
        registry.add("platform.doris.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SourceService sources;
    @Autowired OdsPipelineService ods;
    @Autowired PipelineService pipelines;
    @Autowired PlatformMapper platform;
    @Autowired ModelMapper models;
    @Autowired ModelingService modeling;
    @Autowired MockMvc mvc;
    @MockitoBean DorisModelGateway gateway;
    private final AtomicReference<DorisModelGateway.Schema> physical = new AtomicReference<>();

    @BeforeEach void prepare() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS source_orders"); jdbc.execute("DROP TABLE IF EXISTS source_without_key");
        jdbc.execute("DROP TABLE IF EXISTS source_composite");
        jdbc.execute("DROP TABLE IF EXISTS source_partitioned");
        jdbc.execute("CREATE TABLE source_orders (payload VARCHAR(12), ID BIGINT NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY(ID), KEY ix_updated(updated_at))");
        jdbc.execute("CREATE TABLE source_without_key (code BIGINT NOT NULL, updated_at DATETIME(6) NOT NULL, KEY ix_updated(updated_at))");
        jdbc.execute("CREATE TABLE source_composite (first_key BIGINT NOT NULL, second_key BIGINT NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY(second_key,first_key), KEY ix_updated(updated_at))");
        jdbc.execute("CREATE TABLE source_partitioned (event_id BIGINT NOT NULL, event_day DATE NOT NULL, payload VARCHAR(12), updated_at DATETIME(6) NOT NULL, PRIMARY KEY(event_id,event_day), KEY ix_updated(updated_at))");
        for (String table : List.of("metadata_column", "metadata_table", "metadata_scope", "model_execution", "model_partition", "model_field", "data_model", "pipeline_run", "pipeline", "data_source", "audit_event")) jdbc.update("DELETE FROM " + table);
        reset(gateway); physical.set(null);
        when(gateway.backendCapacity()).thenReturn(new DorisModelGateway.BackendCapacity(1, 1));
        when(gateway.inspect(anyString())).thenAnswer(call -> Optional.ofNullable(physical.get()).orElse(empty()));
    }

    @Test void previewThenCreatePersistsAtomicMetadataAndPreservesSourceReaderOrder() throws Exception {
        long source = source(); var input = input(source, "source_orders", "ods_auto_orders", List.of());
        var preview = ods.preview(input);
        assertThat(preview.ready()).isTrue();
        assertThat(preview.fields()).extracting(OdsPipelineService.Mapping::sourceName).containsExactly("payload", "ID", "updated_at");
        assertThat(preview.model().getFields()).extracting(f -> f.getName()).containsExactly("id", "payload", "updated_at");
        assertThat(preview.model().getFields().get(1).getDataType()).isEqualTo("VARCHAR(48)");
        assertThat(preview.model().getFields()).filteredOn(f->f.getName().equals("updated_at"))
                .extracting(f->f.getDataType()).containsExactly("DATETIME(6)");
        assertThat(preview.warnings()).anyMatch(s -> s.contains("字段前部")).anyMatch(s -> s.contains("小写"));
        assertThat(models.models()).isEmpty(); assertThat(platform.pipelines()).isEmpty(); verify(gateway, never()).create(anyString());
        doAnswer(call -> { physical.set(schema(preview.model())); return null; }).when(gateway).create(anyString());
        long id = ods.create(input, preview.previewHash(), true, "dev");
        assertThat(platform.pipeline(id).getSourceSchemaHash()).isEqualTo(preview.sourceHash());
        assertThat(models.modelByTable("ods_auto_orders")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metadata_table", Integer.class)).isEqualTo(2);
        var claim = pipelines.claim(id);
        @SuppressWarnings("unchecked") var sourceInfo = (Map<String, Object>) claim.get("source");
        assertThat(sourceInfo.get("columns")).isEqualTo(List.of("payload", "ID", "updated_at"));
        assertThat(claim.get("targetColumns")).isEqualTo(List.of("payload", "id", "updated_at"));
    }

    @Test void requiresManualKeyForKeylessSourceAndRejectsUnconfirmedOrStalePreviewWithoutWrites() throws Exception {
        long source = source(); var noKey = input(source, "source_without_key", "ods_keyless", List.of());
        var preview = ods.preview(noKey);
        assertThat(preview.ready()).isFalse(); assertThat(preview.warnings()).anyMatch(s -> s.contains("没有主键"));
        var manual = input(source, "source_without_key", "ods_keyless", List.of("code"));
        var ready = ods.preview(manual); assertThat(ready.ready()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> ods.create(manual, ready.previewHash(), false, "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> ods.create(manual, "forged", true, "dev"));
        jdbc.execute("ALTER TABLE source_without_key ADD COLUMN changed VARCHAR(2)");
        assertThatIllegalArgumentException().isThrownBy(() -> ods.create(manual, ready.previewHash(), true, "dev")).withMessageContaining("变化");
        assertThat(models.models()).isEmpty(); assertThat(platform.pipelines()).isEmpty(); verify(gateway, never()).create(anyString());
    }

    @Test void gatewayFailureRollsBackModelPipelineAndMetadata() throws Exception {
        long source = source(); var input = input(source, "source_orders", "ods_fail", List.of()); var preview = ods.preview(input);
        doThrow(new SQLException("unavailable")).when(gateway).create(anyString());
        assertThatIllegalArgumentException().isThrownBy(() -> ods.create(input, preview.previewHash(), true, "dev")).withMessageContaining("未完成");
        assertThat(models.models()).isEmpty(); assertThat(platform.pipelines()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metadata_table", Integer.class)).isZero();
    }

    @Test void compositePrimaryKeyOrderSurvivesCheckboxSubmissionOrder() throws Exception {
        long source=source();
        var preview=ods.preview(input(source,"source_composite","ods_composite",List.of()));
        assertThat(preview.model().getFields()).filteredOn(f->f.isKeyColumn()).extracting(f->f.getName())
                .containsExactly("second_key","first_key");
        var posted=input(source,"source_composite","ods_composite",List.of("first_key","second_key"));
        assertThat(ods.preview(posted).previewHash()).isEqualTo(preview.previewHash());
        doAnswer(call->{physical.set(schema(preview.model())); return null;}).when(gateway).create(anyString());
        assertThat(ods.create(posted,preview.previewHash(),true,"dev")).isPositive();
    }

    @Test void keylessSourceCanUseConfirmedBusinessKeyWithoutInventingASourcePrimaryKey() throws Exception {
        long source=source(); var input=input(source,"source_without_key","ods_business_key",List.of("code"));
        var preview=ods.preview(input);
        doAnswer(call->{physical.set(schema(preview.model())); return null;}).when(gateway).create(anyString());
        long id=ods.create(input,preview.previewHash(),true,"dev");
        assertThat(platform.pipeline(id).getSourceKeyColumns()).isEqualTo("code");
        assertThat(models.fields(models.modelByTable("ods_business_key").getId())).filteredOn(f->f.isKeyColumn())
                .extracting(f->f.getName()).containsExactly("code");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metadata_column c JOIN metadata_table t ON t.id=c.table_id WHERE t.scope_key=? AND c.key_column=TRUE",Integer.class,"source:"+source)).isZero();
    }

    @Test void adoptsMatchingPhysicalTableWithoutCreateAndGuardStopsDriftBeforeRun() throws Exception {
        long source = source(); var input = input(source, "source_orders", "ods_adopt", List.of()); var preview = ods.preview(input);
        physical.set(schema(preview.model()));
        long id = ods.create(input, preview.previewHash(), true, "dev"); verify(gateway, never()).create(anyString());
        jdbc.execute("ALTER TABLE source_orders ADD COLUMN altered INT");
        assertThatIllegalArgumentException().isThrownBy(() -> pipelines.validateExecutable(platform.pipeline(id))).withMessageContaining("源表结构已变化");
        assertThatIllegalArgumentException().isThrownBy(() -> pipelines.claim(id)).withMessageContaining("源表结构已变化");
        assertThat(platform.pipeline(id).getWatermark()).isEqualTo(java.time.LocalDateTime.of(1970, 1, 1, 0, 0));
        assertThat(platform.pipeline(id).getActiveRunId()).isNull();
    }

    @Test void previewRejectsExistingPhysicalStructureThatDoesNotMatchGeneratedOds() {
        long source = source();
        physical.set(new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("wrong", "BIGINT", false, "")),
                List.of("wrong"), List.of("wrong"), 1, "UNIQUE", 1, "NONE", "", "DAY", Map.of(), true, true));
        assertThatIllegalArgumentException().isThrownBy(() -> ods.preview(input(source, "source_orders", "ods_conflict", List.of())))
                .withMessageContaining("结构不同");
        assertThat(models.models()).isEmpty(); assertThat(platform.pipelines()).isEmpty();
    }

    @Test void guardRejectsRegisteredModelAndPhysicalDriftWithoutStartingRun() throws Exception {
        long source = source(); var input = input(source, "source_orders", "ods_guard", List.of()); var preview = ods.preview(input);
        physical.set(schema(preview.model())); long id = ods.create(input, preview.previewHash(), true, "dev");
        physical.set(empty());
        assertThatIllegalArgumentException().isThrownBy(()->pipelines.claim(id)).withMessageContaining("物理表");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_run",Integer.class)).isZero();
        physical.set(schema(preview.model()));
        var model = modeling.require(models.modelByTable("ods_guard").getId());
        model.getFields().get(1).setDataType("STRING"); modeling.save(model.getId(), model, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> pipelines.validateExecutable(platform.pipeline(id))).withMessageContaining("模型字段已变化");
        assertThat(platform.pipeline(id).getActiveRunId()).isNull();
    }

    @Test void newPageDefaultsAutoAndPreviewPostRequiresDeveloperAndCsrf() throws Exception {
        mvc.perform(get("/pipelines/new").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"AUTO\"")));
        mvc.perform(post("/pipelines/preview").with(user("dev").roles("DEVELOPER"))).andExpect(status().isForbidden());
        mvc.perform(post("/pipelines/preview").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test void noScriptModeSwitchDiscardsOldTargetAndConfirmationWithoutWrites() throws Exception {
        var result=mvc.perform(post("/pipelines/preview").with(csrf()).with(user("dev").roles("DEVELOPER"))
                .param("targetMode","EXISTING").param("targetTable","ods_stale")
                .param("previewHash","stale").param("confirmed","true").param("keyColumns","id"))
                .andExpect(status().isOk()).andReturn();
        var draft=(com.litedata.web.ResourceForms.PipelineDraft)result.getModelAndView().getModel().get("draft");
        assertThat(draft.getTargetMode()).isEqualTo("EXISTING");
        assertThat(draft.getTargetTable()).isEmpty(); assertThat(draft.getKeyColumns()).isEmpty();
        assertThat(draft.getPreviewHash()).isEmpty(); assertThat(draft.isConfirmed()).isFalse();
        assertThat(models.models()).isEmpty(); assertThat(platform.pipelines()).isEmpty();
        verify(gateway,never()).create(anyString());
    }

    @Test void wizardPhysicalSettingsRoundTripIntoTheCreatedModelAndSchemaGuard() throws Exception {
        long source=source();
        var result=mvc.perform(physicalRequest("/pipelines/preview",source)
                .param("wizardStep","3").param("modelType","DUPLICATE"))
                .andExpect(status().isOk()).andReturn();
        var preview=(OdsPipelineService.Preview)result.getModelAndView().getModel().get("odsPreview");
        var draft=(com.litedata.web.ResourceForms.PipelineDraft)result.getModelAndView().getModel().get("draft");
        assertThat(draft.getWizardStep()).isEqualTo(3);
        assertThat(preview.ready()).isTrue();
        assertThat(preview.ddl()).contains("UNIQUE KEY", "AUTO PARTITION BY RANGE", "DISTRIBUTED BY HASH(`event_id`) BUCKETS 4");
        doAnswer(call->{physical.set(schema(preview.model())); return null;}).when(gateway).create(anyString());
        mvc.perform(physicalRequest("/pipelines",source).param("previewHash",draft.getPreviewHash()).param("confirmed","true"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pipelines"));
        var saved=modeling.require(models.modelByTable("ods_wizard").getId());
        assertThat(saved.getModelType()).isEqualTo("UNIQUE"); assertThat(saved.getDistributionColumns()).isEqualTo("event_id");
        assertThat(saved.getPartitionType()).isEqualTo("AUTO_RANGE"); assertThat(saved.getPartitionColumn()).isEqualTo("event_day");
        assertThat(saved.getBuckets()).isEqualTo(4); assertThat(saved.isAutoBuckets()).isFalse();
        pipelines.validateExecutable(platform.pipelines().getFirst());
    }

    @Test void allPartitionModesReachTheValidatedDdlAndPhysicalChangesRequireFreshConfirmation() throws Exception {
        long source=source();
        var range=new com.litedata.model.ModelPartition(); range.setName("p0"); range.setLowerBound("0"); range.setUpperBound("100");
        var list=new com.litedata.model.ModelPartition(); list.setName("p0"); list.setValuesText("1\n2");
        var definitions=List.of(
                physical("AUTO_LIST","event_id",List.of()),physical("MANUAL_RANGE","event_id",List.of(range)),
                physical("MANUAL_LIST","event_id",List.of(list)),physical("DYNAMIC","event_day",List.of()));
        for(var definition:definitions) {
            var input=physicalInput(source,definition,4);
            var plan=ods.preview(input);
            assertThat(plan.ready()).isTrue(); assertThat(plan.model().getPartitionType()).isEqualTo(definition.partitionType());
            assertThat(plan.ddl()).contains("PARTITION BY", "DISTRIBUTED BY HASH(`event_id`) BUCKETS 4");
            assertThatIllegalArgumentException().isThrownBy(()->ods.create(physicalInput(source,definition,5),plan.previewHash(),true,"dev"))
                    .withMessageContaining("重新读取结构");
        }
        assertThatIllegalArgumentException().isThrownBy(()->ods.preview(physicalInput(source,physical("AUTO_RANGE","updated_at",List.of()),4)))
                .withMessageContaining("键列");
        assertThat(models.models()).isEmpty(); assertThat(platform.pipelines()).isEmpty(); verify(gateway,never()).create(anyString());
    }

    @Test void invalidPhysicalSettingsKeepSourceFieldsForRepairButNoExecutablePreview() throws Exception {
        long source=source();
        var result=mvc.perform(physicalRequest("/pipelines/preview",source).param("partitionColumn","updated_at"))
                .andExpect(status().isOk()).andReturn();
        var preview=(OdsPipelineService.Preview)result.getModelAndView().getModel().get("odsPreview");
        assertThat(preview.fields()).hasSize(4); assertThat(preview.ready()).isFalse();
        assertThat(preview.previewHash()).isEmpty(); assertThat(preview.ddl()).isEmpty();
        var draft=(com.litedata.web.ResourceForms.PipelineDraft)result.getModelAndView().getModel().get("draft");
        assertThat(draft.getKeyColumns()).containsExactly("event_id","event_day");
        assertThat(models.models()).isEmpty(); assertThat(platform.pipelines()).isEmpty();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder physicalRequest(String path,long source) {
        return post(path).with(csrf()).with(user("dev").roles("DEVELOPER"))
                .param("name","wizard-sync").param("sourceId",Long.toString(source)).param("sourceTable","source_partitioned")
                .param("targetMode","AUTO").param("targetTable","ods_wizard").param("watermarkColumn","updated_at")
                .param("cronExpression","0 0 * * * ?").param("autoBuckets","false").param("buckets","4").param("replicationNum","1")
                .param("distributionType","HASH").param("distributionColumns","event_id")
                .param("partitionType","AUTO_RANGE").param("partitionColumn","event_day").param("partitionUnit","DAY");
    }
    private static OdsPipelineService.PhysicalSettings physical(String type,String column,List<com.litedata.model.ModelPartition> partitions) {
        return new OdsPipelineService.PhysicalSettings("HASH","event_id",type,column,"DAY","FIXED",DataModel.DEFAULT_DYNAMIC_PARTITION_PROPERTIES,partitions);
    }
    private static OdsPipelineService.Input physicalInput(long source,OdsPipelineService.PhysicalSettings physical,int buckets) {
        return new OdsPipelineService.Input("physical-sync",source,"source_partitioned","updated_at","ods_physical","0 0 * * * ?",List.of(),false,buckets,1,physical);
    }

    private long source() {
        sources.create("ods-source", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        return platform.sources().getFirst().getId();
    }
    private static OdsPipelineService.Input input(long source, String table, String target, List<String> keys) {
        return new OdsPipelineService.Input("sync-" + target, source, table, "updated_at", target, "0 0 * * * ?", keys, true, 0, 1);
    }
    private static DorisModelGateway.Schema empty() {
        return new DorisModelGateway.Schema(List.of(), List.of(), List.of(), 0, "UNIQUE", 1, "NONE", "", "DAY", Map.of(), true, true);
    }
    private static DorisModelGateway.Schema schema(DataModel model) {
        return new DorisModelGateway.Schema(model.getFields().stream().map(f -> new DorisModelGateway.Column(f.getName(), f.getDataType(), f.isNullable(), f.getComment())).toList(),
                model.getFields().stream().filter(f -> f.isKeyColumn()).map(f -> f.getName()).toList(),
                model.getDistributionColumns().isBlank() ? List.of() : List.of(model.getDistributionColumns().split(",")), model.getBuckets(),
                "UNIQUE", model.getReplicationNum(), model.getPartitionType(), model.getPartitionColumn(), model.getPartitionUnit(), Map.of(), true, model.isAutoBuckets());
    }
}
