package com.litedata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.litedata.data.SourceService;
import com.litedata.model.DataService;
import com.litedata.model.DataSource;
import com.litedata.model.Pipeline;
import com.litedata.model.PipelineRun;
import com.litedata.pipeline.PipelineService;
import com.litedata.service.DataApiService;
import com.litedata.store.PlatformMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.web.server.ResponseStatusException;

/** Exercises migrations, MVC rendering, and critical state transitions against a real MySQL instance. */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlatformIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform")
            .withUsername("platform")
            .withPassword("platform-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token", () -> "integration-test-internal-token-0123456789");
        registry.add("platform.admin-password", () -> "IntegrationAdminPassword2026");
        registry.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("platform.doris.username", MYSQL::getUsername);
        registry.add("platform.doris.password", MYSQL::getPassword);
        registry.add("spring.thymeleaf.cache", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformMapper mapper;
    @Autowired SourceService sourceService;
    @Autowired PipelineService pipelineService;
    @Autowired DataApiService apiService;

    @BeforeEach
    void setUp() throws Exception {
        try(var root=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()); var statement=root.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS warehouse");
            statement.execute("GRANT ALL ON warehouse.* TO 'platform'@'%'");
        }
        jdbc.execute("CREATE DATABASE IF NOT EXISTS warehouse");
        jdbc.execute("CREATE TABLE IF NOT EXISTS warehouse.api_fixture (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS source_orders (id BIGINT PRIMARY KEY, updated_at TIMESTAMP(6) NOT NULL, INDEX ix_source_orders_updated_at (updated_at))");
        jdbc.update("DELETE FROM api_credential");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM pipeline");
        jdbc.update("DELETE FROM data_service");
        jdbc.update("DELETE FROM data_source");
        jdbc.update("DELETE FROM warehouse.api_fixture");
        jdbc.update("DELETE FROM source_orders");
        jdbc.update("INSERT INTO warehouse.api_fixture(id, name) VALUES (1, 'Ada')");
        jdbc.update("INSERT INTO source_orders(id, updated_at) VALUES (1, CURRENT_TIMESTAMP(6))");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM api_credential");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM pipeline");
        jdbc.update("DELETE FROM data_service");
        jdbc.update("DELETE FROM data_source");
    }

    @Test
    void flywayCreatesPlatformTablesAndProtectedPagesRender() throws Exception {
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('data_source','pipeline','pipeline_run','data_service','api_credential')", Integer.class);
        assertThat(tables).isEqualTo(5);

        mvc.perform(get("/sources")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/login")));
        for (String page : new String[] {"/", "/sources", "/pipelines", "/services"}) {
            mvc.perform(get(page).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"));
        }
    }

    @Test
    void csrfRejectsPageMutationsWithoutAToken() throws Exception {
        mvc.perform(post("/sources").with(user("admin").roles("ADMIN"))
                        .param("name", "without-csrf")
                        .param("jdbcUrl", MYSQL.getJdbcUrl())
                        .param("username", MYSQL.getUsername())
                        .param("password", MYSQL.getPassword()))
                .andExpect(status().isForbidden());
    }

    @Test
    void databaseLoginAndRolesAreEnforced() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","IntegrationAdminPassword2026"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/"));
        mvc.perform(post("/sources").with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/users").with(user("developer").roles("DEVELOPER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/services").with(user("admin").roles("ADMIN")).flashAttr("apiKey","ld_once_visible"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ld_once_visible")));
    }

    @Test
    void dataSourceCredentialsAreEncryptedAtRest() {
        String secret = "a-source-password-that-must-not-be-stored";
        sourceService.create("encrypted-source", MYSQL.getJdbcUrl(), MYSQL.getUsername(), secret);

        DataSource source = mapper.sources().getFirst();
        String persisted = jdbc.queryForObject("SELECT encrypted_password FROM data_source WHERE id=?", String.class, source.getId());
        assertThat(source.getEncryptedPassword()).isEqualTo(persisted);
        assertThat(persisted).doesNotContain(secret);
        assertThat(sourceService.password(source)).isEqualTo(secret);
    }

    @Test
    void apiRequiresPublicationAndCurrentCredential() throws Exception {
        apiService.create("客户查询", "customers", "SELECT id, name FROM warehouse.api_fixture WHERE id = :customerId", 100);
        DataService service = mapper.services().getFirst();

        mvc.perform(get("/api/data/customers").param("customerId", "1"))
                .andExpect(status().isNotFound());

        apiService.publish(service.getId());
        mvc.perform(get("/api/data/customers").param("customerId", "1"))
                .andExpect(status().isUnauthorized());

        String first = apiService.credential(service.getId());
        mvc.perform(get("/api/data/customers").header("X-API-Key", first).param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ada")));

        String rotated = apiService.credential(service.getId());
        mvc.perform(get("/api/data/customers").header("X-API-Key", first).param("customerId", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/data/customers").header("X-API-Key", rotated).param("customerId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void claimHasSingleOwnerAndFinishPreservesWatermarkSemantics() {
        Pipeline pipeline = createPipeline();
        LocalDateTime originalWatermark = mapper.pipeline(pipeline.getId()).getWatermark();

        Map<String, Object> firstClaim = pipelineService.claim(pipeline.getId());
        long firstRunId = (Long) firstClaim.get("runId");
        ResponseStatusException conflict = catchThrowableOfType(
                () -> pipelineService.claim(pipeline.getId()), ResponseStatusException.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        pipelineService.finish(firstRunId, false, "worker failed");
        Pipeline afterFailure = mapper.pipeline(pipeline.getId());
        assertThat(afterFailure.getWatermark()).isEqualTo(originalWatermark);
        assertThat(afterFailure.getActiveRunId()).isNull();
        assertThat(mapper.run(firstRunId).getStatus()).isEqualTo("FAILED");

        Map<String, Object> secondClaim = pipelineService.claim(pipeline.getId());
        long secondRunId = (Long) secondClaim.get("runId");
        PipelineRun successfulRun = mapper.run(secondRunId);
        pipelineService.finish(secondRunId, true, "worker completed");
        Pipeline afterSuccess = mapper.pipeline(pipeline.getId());
        assertThat(afterSuccess.getWatermark()).isEqualTo(successfulRun.getUpperBound());
        assertThat(mapper.run(secondRunId).getStatus()).isEqualTo("SUCCESS");

        pipelineService.finish(secondRunId, false, "replayed acknowledgement");
        assertThat(mapper.pipeline(pipeline.getId()).getWatermark()).isEqualTo(successfulRun.getUpperBound());
        assertThat(mapper.run(secondRunId).getStatus()).isEqualTo("SUCCESS");
    }

    private Pipeline createPipeline() {
        sourceService.create("source-for-pipeline", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        DataSource source = mapper.sources().getFirst();
        pipelineService.create("orders-hourly", source.getId(), "source_orders", "updated_at", "ods_orders",
                "INSERT INTO ods_orders SELECT * FROM source_orders", "0 0 * * * ?");
        return mapper.pipelines().getFirst();
    }
}
