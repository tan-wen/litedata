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

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM model_execution"); jdbc.update("DELETE FROM model_field");
        jdbc.update("DELETE FROM data_model"); jdbc.update("DELETE FROM audit_event");
        reset(gateway);
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
        verifyNoInteractions(gateway);
    }

    @Test void staleSavesAndStaleOrForgedPreviewsCannotExecute() {
        var preview = fixture(); var input = service.require(preview.model().getId());
        service.save(input.getId(), input, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(input.getId(), input, "other"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.physicalize(input.getId(), 1, preview.hash(), "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.physicalize(input.getId(), 2, "tampered", "dev"));
        assertThat(service.require(input.getId()).getVersion()).isEqualTo(2);
        assertThat(mapper.executions(input.getId())).isEmpty(); verifyNoInteractions(gateway);
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
