package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.model.*;
import com.litedata.modeling.*;
import com.litedata.store.ModelMapper;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Real MySQL metadata/migrations and MVC; the external Doris boundary is explicitly mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ModelingIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("modeling-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token", () -> "modeling-test-internal-token-0123456789");
        registry.add("platform.admin-password", () -> "ModelingAdminPassword2026");
        registry.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("platform.doris.username", MYSQL::getUsername);
        registry.add("platform.doris.password", MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelingService service;
    @Autowired ModelMapper mapper;
    @MockitoBean DorisModelGateway gateway;

    @BeforeEach void clean() throws SQLException {
        jdbc.update("DELETE FROM model_execution"); jdbc.update("DELETE FROM model_field");
        jdbc.update("DELETE FROM data_model"); jdbc.update("DELETE FROM audit_event");
        reset(gateway);
        when(gateway.backendCapacity()).thenReturn(new DorisModelGateway.BackendCapacity(3, 3));
    }

    @Test void createsEditsFiltersAndRendersModelFormsAndDdl() throws Exception {
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "ods_model_test").param("name", "订单模型").param("subjectArea", "交易域")
                .param("layer", "ODS").param("buckets", "1")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true")
                .param("fields[1].name", "amount").param("fields[1].dataType", "DECIMAL(18,2)")
                .param("fields[1].nullable", "true").param("fields[1].standard", "STD-001")
                .param("physicalVersion", "99").param("archived", "true"))
                .andExpect(status().is3xxRedirection());
        var model = mapper.models().getFirst();
        var saved = service.require(model.getId());
        assertThat(saved.getFields()).hasSize(2);
        assertThat(saved.getFields().getFirst().isKeyColumn()).isTrue();
        assertThat(saved.getFields().getFirst().isNullable()).isFalse();
        assertThat(saved.getFields().get(1).isKeyColumn()).isFalse();
        assertThat(saved.getFields().get(1).getStandard()).isEqualTo("STD-001");
        assertThat(saved.getPhysicalVersion()).isNull();
        for (String page : List.of("/models", "/models/new", "/models/" + model.getId(), "/models/" + model.getId() + "/edit"))
            mvc.perform(get(page).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/models").param("keyword", "交易域").param("layer", "ODS").with(user("dev").roles("DEVELOPER")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ods_model_test")));
        assertThat(service.list("订单", "DWD")).isEmpty();
        saved.setName("新名称"); saved.getFields().get(1).setNullable(false);
        service.save(saved.getId(), saved, "dev");
        assertThat(service.require(saved.getId()).getVersion()).isEqualTo(2);
        assertThat(service.require(saved.getId()).getFields().get(1).isNullable()).isFalse();
    }

    @Test void invalidDefinitionPreservesFormAndDoesNotPersist() throws Exception {
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "ods_bad").param("name", "保留输入").param("subjectArea", "交易")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT); DROP TABLE x"))
                .andExpect(status().isOk()).andExpect(view().name("model-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("保留输入")));
        assertThat(mapper.models()).isEmpty();
    }

    @Test void rendersTypeDropdownsAndPreservesParameterizedFieldTypesOnEdit() throws Exception {
        var model = service.require(fixture().model().getId());
        for (String type : List.of("CHAR(255)", "VARCHAR(65533)", "DECIMAL(38,38)", "DATETIME(6)")) {
            var field = new ModelField(); field.setName("field_" + model.getFields().size()); field.setDataType(type);
            model.getFields().add(field);
        }
        service.save(model.getId(), model, "dev");
        String html = mvc.perform(get("/models/" + model.getId() + "/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<select", "data-property=\"dataType\"", "CHAR(255)", "VARCHAR(65533)", "DECIMAL(38,38)", "DATETIME(6)",
                        "data-type-kind=\"BIGINT\"", "data-type-kind=\"DATE\"", "data-type-kind=\"VARCHAR\"", ">DATETIME</option>")
                .doesNotContain("list=\"field-types\"", "id=\"field-types\"");
        assertThat(java.util.regex.Pattern.compile("<input\\b[^>]*data-property=\"dataType\"").matcher(html).find()).isFalse();
        assertThat(java.util.regex.Pattern.compile("<select\\b[^>]*data-property=\"dataType\"").matcher(html).results().count())
                .isEqualTo(model.getFields().size() + 1L); // Includes the dynamic new-row template.
    }

    @Test void persistsPhysicalSettingsAggregationsAndEditableFormValues() throws Exception {
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "dws_configured").param("name", "按月指标").param("subjectArea", "交易")
                .param("layer", "DWS").param("modelType", "AGGREGATE").param("replicationNum", "3").param("buckets", "4")
                .param("partitionType", "AUTO_RANGE").param("partitionColumn", "dt").param("partitionUnit", "MONTH")
                .param("fields[0].name", "dt").param("fields[0].dataType", "DATE").param("fields[0].keyColumn", "true")
                .param("fields[1].name", "total").param("fields[1].dataType", "DECIMAL(18,2)").param("fields[1].aggregation", "SUM"))
                .andExpect(status().is3xxRedirection());
        var saved = service.require(mapper.models().getFirst().getId());
        assertThat(saved.getModelType()).isEqualTo("AGGREGATE");
        assertThat(saved.getReplicationNum()).isEqualTo(3);
        assertThat(saved.getPartitionType()).isEqualTo("AUTO_RANGE");
        assertThat(saved.getPartitionColumn()).isEqualTo("dt");
        assertThat(saved.getPartitionUnit()).isEqualTo("MONTH");
        assertThat(saved.getFields().get(1).getAggregation()).isEqualTo("SUM");
        mvc.perform(get("/models/" + saved.getId() + "/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"3\"")));
        mvc.perform(get("/models/" + saved.getId()).with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("AGGREGATE KEY")));
        var oldHash = service.preview(saved.getId()).hash();
        saved.setReplicationNum(2); saved.setPartitionUnit("DAY");
        service.save(saved.getId(), saved, "dev");
        var edited = service.require(saved.getId());
        assertThat(edited.getReplicationNum()).isEqualTo(2);
        assertThat(edited.getPartitionUnit()).isEqualTo("DAY");
        assertThat(edited.getVersion()).isEqualTo(2);
        assertThat(service.preview(saved.getId()).hash()).isNotEqualTo(oldHash);
        assertThatIllegalArgumentException().isThrownBy(() -> service.physicalize(edited.getId(), 1, oldHash, "dev"));
        verify(gateway, never()).create(anyString());
        verify(gateway, never()).inspect(anyString());
    }

    @Test void invalidPhysicalSettingsPreserveUserInputWithoutSaving() throws Exception {
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "bad_config").param("name", "测试").param("subjectArea", "测试")
                .param("modelType", "DUPLICATE").param("replicationNum", "11")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true"))
                .andExpect(status().isOk()).andExpect(view().name("model-form"))
                .andExpect(model().attributeExists("error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"11\"")));
        assertThat(mapper.models()).isEmpty();
    }

    @Test void backendCapacitySetsFormLimitAndIsRecheckedOnSaveAndPhysicalize() throws Exception {
        when(gateway.backendCapacity()).thenReturn(new DorisModelGateway.BackendCapacity(4, 2));
        mvc.perform(get("/models/new").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(replicaAttribute("max", "2"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-step-summary=\"3\">可用 BE：2 / 共 4 个</small>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("当前可用 BE：2 / 共 4 个")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("当前单机环境请填 1"))));
        var preview = fixture();
        var model = service.require(preview.model().getId());
        model.setReplicationNum(2);
        service.save(model.getId(), model, "dev");
        preview = service.preview(model.getId());

        // A node goes down after opening the form: the submitted hint/max cannot bypass the fresh check.
        when(gateway.backendCapacity()).thenReturn(new DorisModelGateway.BackendCapacity(4, 1));
        mvc.perform(get("/models/" + model.getId() + "/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(replicaAttribute("max", "1"))
                .andExpect(replicaAttribute("value", "2"));
        mvc.perform(post("/models/" + model.getId()).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("version", "2").param("tableName", model.getTableName()).param("name", "保留输入").param("subjectArea", "交易")
                .param("replicationNum", "2").param("backendStatus.available", "99")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true"))
                .andExpect(status().isOk()).andExpect(view().name("model-form"))
                .andExpect(model().attribute("errorStep", 3))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("副本数不能超过当前可用 BE 节点数（1 个）")))
                .andExpect(replicaAttribute("value", "2"));
        assertThat(service.require(model.getId()).getVersion()).isEqualTo(2);
        assertThat(service.require(model.getId()).getName()).isEqualTo("测试模型");
        var result = service.physicalize(model.getId(), 2, preview.hash(), "dev");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("1 个");
        assertThat(mapper.executions(model.getId()).getFirst().getStatus()).isEqualTo("FAILED");
        verify(gateway, never()).create(anyString());
    }

    @Test void zeroAvailableBackendsRejectsNewModelRatherThanAssumingOne() throws Exception {
        when(gateway.backendCapacity()).thenReturn(new DorisModelGateway.BackendCapacity(2, 0));
        mvc.perform(get("/models/new").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(replicaAttribute("max", "0"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("当前可用 BE：0 / 共 2 个")));
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "ods_no_backend").param("name", "暂不可用").param("subjectArea", "测试")
                .param("replicationNum", "1").param("fields[0].name", "id")
                .param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true"))
                .andExpect(status().isOk()).andExpect(model().attribute("errorStep", 3));
        assertThat(mapper.models()).isEmpty();
    }

    @Test void failedBackendQueryShowsUnknownWithoutLeakingErrorAndAllowsOfflineModeling() throws Exception {
        when(gateway.backendCapacity()).thenThrow(new SQLException("password=do-not-leak", "42000", 1142));
        mvc.perform(get("/models/new").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(replicaAttribute("max", "10"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未获取到当前 BE 数量")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-step-summary=\"3\">BE 数量暂未获取</small>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-leak"))));
        var model = service.require(fixture().model().getId());
        model.setReplicationNum(3);
        service.save(model.getId(), model, "dev");
        assertThat(service.require(model.getId()).getReplicationNum()).isEqualTo(3);
        doReturn(new DorisModelGateway.BackendCapacity(12, 12)).when(gateway).backendCapacity();
        mvc.perform(get("/models/" + model.getId() + "/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(replicaAttribute("max", "10"));
    }

    @Test void newFormsDefaultToAutoWhileLegacyEditsRetainTheirFixedBucketDefinition() throws Exception {
        mvc.perform(get("/models/new").param("layer", "DWS").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(model().attribute("draft", org.hamcrest.Matchers.hasProperty("autoBuckets", org.hamcrest.Matchers.is(true))))
                .andExpect(model().attribute("draft", org.hamcrest.Matchers.hasProperty("layer", org.hamcrest.Matchers.is("DWS"))));
        var legacy = service.require(fixture().model().getId());
        legacy.setBuckets(4); service.save(legacy.getId(), legacy, "dev");
        assertThat(service.require(legacy.getId()).isAutoBuckets()).isFalse();
        assertThat(service.preview(legacy.getId()).ddl()).contains("BUCKETS 4");
        mvc.perform(get("/models/" + legacy.getId() + "/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(model().attribute("draft", org.hamcrest.Matchers.hasProperty("autoBuckets", org.hamcrest.Matchers.is(false))))
                .andExpect(model().attribute("draft", org.hamcrest.Matchers.hasProperty("buckets", org.hamcrest.Matchers.is(4))));
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "dwd_auto").param("name", "自动分桶").param("subjectArea", "测试")
                .param("layer", "DWD").param("autoBuckets", "true")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true"))
                .andExpect(status().is3xxRedirection());
        var automatic = mapper.modelByTable("dwd_auto");
        assertThat(automatic.isAutoBuckets()).isTrue();
        assertThat(service.preview(automatic.getId()).ddl()).contains("BUCKETS AUTO");
        mvc.perform(get("/models/" + automatic.getId()).with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("AUTO（由 Doris 推算）")));
    }

    @Test void validationReturnsToTheStepContainingTheInvalidInput() throws Exception {
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "bad_steps").param("name", "测试").param("subjectArea", "测试")
                .param("fields[0].name", "id").param("fields[0].dataType", "STRING").param("fields[0].keyColumn", "true"))
                .andExpect(status().isOk()).andExpect(model().attribute("errorStep", 2));
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "bad_steps").param("name", "测试").param("subjectArea", "测试")
                .param("replicationNum", "11").param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true"))
                .andExpect(status().isOk()).andExpect(model().attribute("errorStep", 3));
        assertThat(mapper.models()).isEmpty();
    }

    @Test void dimensionFeaturesRoundTripThroughFormsAndPersistAcrossEdits() throws Exception {
        mvc.perform(get("/models/new").param("layer", "DIM").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("公共维度特性")));
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "dim_enterprise").param("name", "公共企业维度").param("subjectArea", "园区")
                .param("layer", "DIM").param("dimensionKeys", "ID").param("dimensionLabel", "label").param("dimensionStrategy", "SCD1")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true")
                .param("fields[1].name", "label").param("fields[1].dataType", "VARCHAR(128)"))
                .andExpect(status().is3xxRedirection());
        var model = service.require(mapper.models().getFirst().getId());
        assertThat(model.getDimensionKeys()).isEqualTo("id");
        assertThat(model.getDimensionLabel()).isEqualTo("label");
        assertThat(model.getDimensionStrategy()).isEqualTo("SCD1");
        assertThat(service.preview(model.getId()).ddl()).contains("UNIQUE KEY(`id`)");
        mvc.perform(get("/models").param("layer", "DIM").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 1L));
        for (String path : List.of("/models/" + model.getId(), "/models/" + model.getId() + "/edit"))
            mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/models/" + model.getId()).with(user("dev").roles("DEVELOPER")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("展示名称")));
        model.setDimensionLabel(""); service.save(model.getId(), model, "dev");
        assertThat(service.require(model.getId()).getDimensionLabel()).isEmpty();
        assertThat(service.require(model.getId()).getDimensionStrategy()).isEqualTo("SCD1");
        model = service.require(model.getId()); model.setLayer("DWD"); service.save(model.getId(), model, "dev");
        var converted = service.require(model.getId());
        assertThat(converted.getDimensionKeys()).isEmpty();
        assertThat(converted.getDimensionStrategy()).isEmpty();
    }

    @Test void invalidDimensionPreservesItsConfigurationAndDoesNotSave() throws Exception {
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "dim_invalid").param("name", "错误维度").param("subjectArea", "园区")
                .param("layer", "DIM").param("dimensionKeys", "missing").param("dimensionLabel", "id")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true"))
                .andExpect(status().isOk()).andExpect(view().name("model-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("维度业务键字段不存在")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"missing\"")));
        assertThat(mapper.models()).isEmpty();
    }

    @Test void authenticationRolesCsrfAndConfirmationAreEnforced() throws Exception {
        var preview = fixture(); long id = preview.model().getId();
        mvc.perform(get("/models")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/models/" + id).with(user("reader").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("执行建表</button>"))));
        for (String path : List.of("/models", "/models/" + id, "/models/" + id + "/archive", "/models/" + id + "/compare", "/models/" + id + "/physicalize"))
            mvc.perform(post(path).with(user("reader").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/models/" + id + "/physicalize").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/models/" + id + "/physicalize").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("version", "1").param("ddlHash", preview.hash()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        verify(gateway, never()).create(anyString());
        verify(gateway, never()).inspect(anyString());
    }

    @Test void staleSavesAndStaleOrForgedPreviewsCannotExecute() throws SQLException {
        var preview = fixture(); var input = service.require(preview.model().getId());
        service.save(input.getId(), input, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(input.getId(), input, "other"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.physicalize(input.getId(), 1, preview.hash(), "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.physicalize(input.getId(), 2, "tampered", "dev"));
        assertThat(service.require(input.getId()).getVersion()).isEqualTo(2);
        assertThat(mapper.executions(input.getId())).isEmpty();
        verify(gateway, never()).create(anyString());
        verify(gateway, never()).inspect(anyString());
    }

    @Test void successfulExecutionIsVersionedAndFailureIsRecordedWithoutLeakingSqlErrors() throws Exception {
        var preview = fixture(); long id = preview.model().getId();
        when(gateway.inspect(anyString())).thenReturn(schema());
        assertThat(service.physicalize(id, 1, preview.hash(), "dev").success()).isTrue();
        assertThat(service.require(id).getPhysicalVersion()).isEqualTo(1);
        var execution = mapper.executions(id).getFirst();
        assertThat(execution.getDdl()).isEqualTo(preview.ddl());
        assertThat(execution.getActor()).isEqualTo("dev"); assertThat(execution.getStatus()).isEqualTo("SUCCESS");
        doThrow(new SQLException("password=do-not-leak this driver message", "42S01", 1050)).when(gateway).create(anyString());
        assertThat(service.physicalize(id, 1, preview.hash(), "dev").success()).isFalse();
        assertThat(mapper.executions(id)).hasSize(2);
        assertThat(mapper.executions(id).getFirst().getMessage()).contains("42S01", "1050").doesNotContain("do-not-leak", "password=");
        mvc.perform(get("/models/" + id).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("最近执行记录")));
    }

    @Test void compareAdoptsMatchingExistingTableAndDetectsDrift() throws Exception {
        var preview = fixture(); long id = preview.model().getId();
        when(gateway.inspect(anyString())).thenReturn(schema());
        mvc.perform(post("/models/" + id + "/compare").with(user("dev").roles("DEVELOPER")).with(csrf()).param("version", "1"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("comparison"));
        assertThat(service.require(id).getPhysicalVersion()).isEqualTo(1);
        when(gateway.inspect(anyString())).thenReturn(new DorisModelGateway.Schema(List.of(
                new DorisModelGateway.Column("id", "BIGINT", true, "")), List.of(), List.of("id"), 1));
        var comparison = service.compare(id, 1, "dev");
        assertThat(comparison.matches()).isFalse();
        assertThat(service.require(id).getPhysicalVersion()).isNull();
        mvc.perform(get("/models/" + id).with(user("dev").roles("DEVELOPER")).flashAttr("comparison", comparison))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("结构比对结果")));
        assertThat(mapper.executions(id).getFirst().getStatus()).isEqualTo("DIFF");
    }

    @Test void archiveRetainsMetadataAndHistoryWithoutTouchingPhysicalTable() throws Exception {
        var preview = fixture(); long id = preview.model().getId();
        when(gateway.inspect(anyString())).thenReturn(schema()); service.compare(id, 1, "dev"); reset(gateway);
        service.archive(id, 1, "dev");
        assertThat(mapper.models()).isEmpty(); assertThat(mapper.fields(id)).hasSize(2);
        assertThat(mapper.executions(id)).hasSize(1);
        assertThatIllegalArgumentException().isThrownBy(() -> service.require(id));
        verifyNoInteractions(gateway);
    }

    @Test void customBucketingPersistsRendersAndUpdatesThroughTheForm() throws Exception {
        mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("tableName", "dwd_buckets").param("name", "自定义分桶").param("subjectArea", "测试")
                .param("layer", "DWD").param("modelType", "DUPLICATE").param("distributionType", "HASH")
                .param("distributionColumns", "region,id").param("autoBuckets", "false").param("buckets", "64")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true")
                .param("fields[1].name", "region").param("fields[1].dataType", "VARCHAR(20)"))
                .andExpect(status().is3xxRedirection());
        var saved = service.require(mapper.models().getFirst().getId());
        assertThat(saved.getDistributionType()).isEqualTo("HASH");
        assertThat(saved.getDistributionColumns()).isEqualTo("region,id");
        assertThat(saved.getBuckets()).isEqualTo(64);
        assertThat(service.preview(saved.getId()).ddl()).contains("DISTRIBUTED BY HASH(`region`, `id`) BUCKETS 64");
        mvc.perform(get("/models/" + saved.getId() + "/edit").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("region,id")));
        mvc.perform(post("/models/" + saved.getId()).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("version", "1").param("tableName", "dwd_buckets").param("name", "随机分桶").param("subjectArea", "测试")
                .param("layer", "DWD").param("modelType", "DUPLICATE").param("distributionType", "RANDOM")
                .param("distributionColumns", "region,id").param("autoBuckets", "true")
                .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true")
                .param("fields[1].name", "region").param("fields[1].dataType", "VARCHAR(20)"))
                .andExpect(status().is3xxRedirection());
        saved = service.require(saved.getId());
        assertThat(saved.getDistributionType()).isEqualTo("RANDOM");
        assertThat(saved.getDistributionColumns()).isEmpty();
        assertThat(saved.isAutoBuckets()).isTrue();
        assertThat(service.preview(saved.getId()).ddl()).contains("DISTRIBUTED BY RANDOM BUCKETS AUTO");
        mvc.perform(get("/models/" + saved.getId()).with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("RANDOM")));
    }

    @Test void invalidBucketingReturnsToPhysicalSettingsAndPreservesSelections() throws Exception {
        for (String buckets : List.of("0", "2147483648", "1.5")) {
            mvc.perform(post("/models").with(user("dev").roles("DEVELOPER")).with(csrf())
                    .param("tableName", "dwd_bad_buckets").param("name", "无效分桶").param("subjectArea", "测试")
                    .param("distributionType", "HASH").param("distributionColumns", "id").param("buckets", buckets)
                    .param("fields[0].name", "id").param("fields[0].dataType", "BIGINT").param("fields[0].keyColumn", "true"))
                    .andExpect(status().isOk()).andExpect(view().name("model-form"))
                    .andExpect(model().attribute("errorStep", 3))
                    .andExpect(model().attribute("draft", org.hamcrest.Matchers.hasProperty("distributionColumns", org.hamcrest.Matchers.is("id"))));
        }
        assertThat(mapper.models()).isEmpty();
    }

    @Test void legacyDistributionDefaultsSurviveSchemaUpgrade() {
        var preview = fixture();
        jdbc.update("UPDATE data_model SET distribution_columns=NULL WHERE id=?", preview.model().getId());
        var legacy = service.require(preview.model().getId());
        assertThat(legacy.getDistributionType()).isEqualTo("HASH");
        assertThat(legacy.getDistributionColumns()).isNull();
        assertThat(service.preview(legacy.getId()).ddl()).isEqualTo(preview.ddl());
    }

    private static org.springframework.test.web.servlet.ResultMatcher replicaAttribute(String attribute, String expected) {
        return result -> {
            var input = java.util.regex.Pattern.compile("<input\\b[^>]*\\bid=\"replicationNum\"[^>]*>")
                    .matcher(result.getResponse().getContentAsString());
            assertThat(input.find()).isTrue();
            assertThat(input.group()).contains(" " + attribute + "=\"" + expected + "\"");
        };
    }

    private ModelingService.Preview fixture() {
        var model = new DataModel(); model.setName("测试模型"); model.setTableName("ods_model_test"); model.setSubjectArea("交易");
        var id = new ModelField(); id.setName("id"); id.setDataType("BIGINT"); id.setKeyColumn(true);
        var amount = new ModelField(); amount.setName("amount"); amount.setDataType("DECIMAL(18,2)"); amount.setNullable(true);
        model.setFields(List.of(id, amount)); return service.preview(service.save(null, model, "dev"));
    }
    private DorisModelGateway.Schema schema() {
        return new DorisModelGateway.Schema(List.of(new DorisModelGateway.Column("id", "bigint(20)", false, ""),
                new DorisModelGateway.Column("amount", "decimalv3(18,2)", true, "")), List.of("id"), List.of("id"), 1);
    }
}
