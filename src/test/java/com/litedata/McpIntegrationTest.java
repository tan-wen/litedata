package com.litedata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.litedata.pipeline.DolphinSchedulerClient;
import com.litedata.security.UserApiKeyService;
import com.litedata.service.DataApiService;
import com.litedata.store.PlatformMapper;
import java.sql.DriverManager;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** MCP authorization contract against Flyway-managed MySQL; scheduling remains a mock. */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class McpIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("platform-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword); r.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token", () -> "mcp-test-internal-token-0123456789"); r.add("platform.admin-password", () -> "McpIntegrationAdminPassword2026");
        r.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl); r.add("platform.doris.username", MYSQL::getUsername); r.add("platform.doris.password", MYSQL::getPassword);
        r.add("spring.thymeleaf.cache", () -> "false");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformMapper platform;
    @Autowired DataApiService services;
    @Autowired UserApiKeyService keys;
    @MockitoBean DolphinSchedulerClient scheduler;

    long publishedId, privateId, pipelineId;
    @BeforeEach void setup() throws Exception {
        reset(scheduler);
        try (var root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); var s = root.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS warehouse"); s.execute("GRANT ALL ON warehouse.* TO 'platform'@'%'");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS warehouse.mcp_fixture (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        cleanup();
        jdbc.update("INSERT INTO warehouse.mcp_fixture(id,name) VALUES(1,'Ada')");
        platform.insertUser("mcp_viewer", "unused", "VIEWER"); platform.insertUser("mcp_other", "unused", "VIEWER"); platform.insertUser("mcp_dev", "unused", "DEVELOPER");
        services.create("MCP published", "mcp-published", "SELECT id,name FROM warehouse.mcp_fixture WHERE id = :id");
        publishedId = platform.serviceBySlug("mcp-published").getId(); services.publish(publishedId);
        services.create("MCP draft", "mcp-draft", "SELECT id FROM warehouse.mcp_fixture"); privateId = platform.serviceBySlug("mcp-draft").getId();
        jdbc.update("INSERT INTO data_source(name,jdbc_url,username,encrypted_password) VALUES('mcp-source',?,'platform','x')", MYSQL.getJdbcUrl());
        long source = jdbc.queryForObject("SELECT id FROM data_source WHERE name='mcp-source'", Long.class);
        jdbc.update("INSERT INTO pipeline(name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) VALUES('mcp-pipeline',?,'source_orders','updated_at','ods_orders','INSERT INTO ods_orders SELECT 1','0 0 * * * ?')", source);
        pipelineId = jdbc.queryForObject("SELECT id FROM pipeline WHERE name='mcp-pipeline'", Long.class);
    }
    @AfterEach void tearDown() { cleanup(); }
    private void cleanup() {
        jdbc.update("DELETE FROM user_api_key_pipeline"); jdbc.update("DELETE FROM user_api_key_service"); jdbc.update("DELETE FROM user_api_key");
        jdbc.update("DELETE FROM api_credential"); jdbc.update("DELETE FROM service_call"); jdbc.update("DELETE FROM audit_event"); jdbc.update("DELETE FROM pipeline_run"); jdbc.update("DELETE FROM pipeline");
        jdbc.update("DELETE FROM data_service"); jdbc.update("DELETE FROM data_source"); jdbc.update("DELETE FROM app_user WHERE username IN ('mcp_viewer','mcp_other','mcp_dev')");
        jdbc.update("DELETE FROM warehouse.mcp_fixture");
    }

    @Test void userKeyPageDisplaysOnceStoresOnlyDigestAndIsOwnerScoped() throws Exception {
        var created = mvc.perform(post("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER")).with(csrf())
                        .param("name", "viewer agent").param("validDays", "90").param("scopes", "services:read", "services:query")
                        .param("serviceSlugs", "mcp-published").param("pipelineIds", ""))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String body = created.getResponse().getContentAsString(); Matcher matcher = Pattern.compile("ld_mcp_[A-Za-z0-9_-]{43}").matcher(body);
        assertThat(matcher.find()).isTrue(); String token = matcher.group();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_api_key WHERE token_hash=?", Integer.class, DataApiService.hash(token))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_api_key WHERE token_hash=?", Integer.class, token)).isZero();
        mvc.perform(get("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
        var other = keys.create("mcp_other", "other", 90, Set.of(UserApiKeyService.READ), "mcp-published", "");
        mvc.perform(get("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("other"))));
        mvc.perform(post("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER")).with(csrf())
                        .param("name", "bad").param("validDays", "90").param("scopes", "pipelines:run").param("pipelineIds", Long.toString(pipelineId)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("请检查")));
        long otherId = other.key().id();
        mvc.perform(post("/settings/api-keys/" + otherId + "/revoke").with(user("mcp_viewer").roles("VIEWER")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER"))
                        .param("name", "csrf")).andExpect(status().isForbidden());
    }

    @Test void mcpProtocolAndCredentialBoundariesWork() throws Exception {
        var issued = keys.create("mcp_viewer", "viewer", 90, Set.of(UserApiKeyService.READ, UserApiKeyService.QUERY), "mcp-published", "");
        String bearer = "Bearer " + issued.token();
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(rpc(1, "initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{}}")))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer")));
        String legacy = services.credential(publishedId);
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + legacy).contentType(MediaType.APPLICATION_JSON).content(rpc(1, "ping", "{}"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/data/mcp-published").header("X-API-Key", issued.token()).param("id", "1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(mcp(bearer, 1, "initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.serverInfo.name").value("litedata"));
        mvc.perform(mcp(bearer, 2, "tools/list", "{}")).andExpect(jsonPath("$.result.tools[?(@.name == 'run_pipeline')]").isEmpty());
        mvc.perform(mcp(bearer, 3, "tools/call", "{\"name\":\"get_current_user\",\"arguments\":{}}"))
                .andExpect(jsonPath("$.result.structuredContent.username").value("mcp_viewer"));
        mvc.perform(mcp(bearer, 4, "tools/call", "{\"name\":\"query_data_service\",\"arguments\":{\"slug\":\"mcp-published\",\"params\":{\"id\":\"1\"}}}"))
                .andExpect(jsonPath("$.result.structuredContent.data[0].name").value("Ada"));
        mvc.perform(mcp(bearer, 5, "tools/call", "{\"name\":\"query_data_service\",\"arguments\":{\"slug\":\"mcp-draft\",\"params\":{}}}"))
                .andExpect(status().isForbidden());
        mvc.perform(mcp(bearer, 6, "tools/call", "{\"name\":\"query_data_service\",\"arguments\":{\"slug\":\"mcp-published\",\"username\":\"admin\",\"role\":\"ADMIN\"}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
        verify(scheduler, never()).run(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void currentUserStateImmediatelyControlsMcpPermissions() throws Exception {
        var viewer = keys.create("mcp_viewer", "viewer", 90, Set.of(UserApiKeyService.READ), "mcp-published", "");
        String viewerBearer = "Bearer " + viewer.token();
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username='mcp_viewer'");
        mvc.perform(mcp(viewerBearer, 1, "tools/list", "{}")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_user SET enabled=TRUE WHERE username='mcp_viewer'");
        jdbc.update("UPDATE user_api_key SET expires_at=UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id=?", viewer.key().id());
        mvc.perform(mcp(viewerBearer, 2, "tools/list", "{}")).andExpect(status().isUnauthorized());
        var dev = keys.create("mcp_dev", "dev", 90, Set.of(UserApiKeyService.RUN), "", Long.toString(pipelineId));
        String devBearer = "Bearer " + dev.token();
        mvc.perform(mcp(devBearer, 3, "tools/list", "{}")).andExpect(jsonPath("$.result.tools[?(@.name == 'run_pipeline')]").isNotEmpty());
        jdbc.update("UPDATE app_user SET role='VIEWER' WHERE username='mcp_dev'");
        mvc.perform(mcp(devBearer, 4, "tools/list", "{}")).andExpect(jsonPath("$.result.tools[?(@.name == 'run_pipeline')]").isEmpty());
        mvc.perform(mcp(devBearer, 5, "tools/call", "{\"name\":\"run_pipeline\",\"arguments\":{\"pipelineId\":" + pipelineId + "}}"))
                .andExpect(status().isForbidden());
        keys.revoke("mcp_dev", dev.key().id());
        mvc.perform(mcp(devBearer, 6, "tools/list", "{}")).andExpect(status().isUnauthorized());
        verify(scheduler, never()).run(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void developerCanRunOnlyItsGrantedPipeline() throws Exception {
        jdbc.update("INSERT INTO pipeline(name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) SELECT 'mcp-other-pipeline',id,'source_orders','updated_at','ods_other','INSERT INTO ods_other SELECT 1','0 0 * * * ?' FROM data_source WHERE name='mcp-source'");
        long otherPipeline = jdbc.queryForObject("SELECT id FROM pipeline WHERE name='mcp-other-pipeline'", Long.class);
        var issued = keys.create("mcp_dev", "runner", 90, Set.of(UserApiKeyService.RUN), "", Long.toString(pipelineId));
        String bearer = "Bearer " + issued.token();
        mvc.perform(mcp(bearer, 1, "tools/call", "{\"name\":\"run_pipeline\",\"arguments\":{\"pipelineId\":" + pipelineId + "}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.structuredContent.status").value("accepted"));
        verify(scheduler).run(pipelineId);
        mvc.perform(mcp(bearer, 2, "tools/call", "{\"name\":\"run_pipeline\",\"arguments\":{\"pipelineId\":" + otherPipeline + "}}"))
                .andExpect(status().isForbidden());
        verify(scheduler, never()).run(otherPipeline);
    }

    @Test void serviceScopesAndPublishedStateBoundDiscoveryAndQuery() throws Exception {
        services.create("MCP other", "mcp-other", "SELECT id FROM warehouse.mcp_fixture");
        long other = platform.serviceBySlug("mcp-other").getId(); services.publish(other);
        var readOnly = keys.create("mcp_viewer", "read-only", 90, Set.of(UserApiKeyService.READ), "mcp-published", "");
        String readBearer = "Bearer " + readOnly.token();
        mvc.perform(mcp(readBearer, 1, "tools/call", "{\"name\":\"query_data_service\",\"arguments\":{\"slug\":\"mcp-published\",\"params\":{\"id\":\"1\"}}}"))
                .andExpect(status().isForbidden());
        var query = keys.create("mcp_dev", "query", 90, Set.of(UserApiKeyService.READ, UserApiKeyService.QUERY), "mcp-published", "");
        String queryBearer = "Bearer " + query.token();
        mvc.perform(mcp(queryBearer, 2, "tools/call", "{\"name\":\"list_data_services\",\"arguments\":{}}"))
                .andExpect(jsonPath("$.result.structuredContent.services.length()").value(1))
                .andExpect(jsonPath("$.result.structuredContent.services[0].slug").value("mcp-published"));
        platform.publish(publishedId, false);
        mvc.perform(mcp(queryBearer, 3, "tools/call", "{\"name\":\"query_data_service\",\"arguments\":{\"slug\":\"mcp-published\",\"params\":{\"id\":\"1\"}}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true)).andExpect(jsonPath("$.result.structuredContent").doesNotExist());
    }

    @Test void keyPagePaginationExpiryAndAbsentScopesAreSafe() throws Exception {
        keys.create("mcp_viewer", "first", 90, Set.of(UserApiKeyService.READ), "mcp-published", "");
        var expired = keys.create("mcp_viewer", "expired", 90, Set.of(UserApiKeyService.READ), "mcp-published", "");
        jdbc.update("UPDATE user_api_key SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", expired.key().id());
        mvc.perform(get("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER")).param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("totalCount", 2L))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("已过期")));
        mvc.perform(get("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER")).param("pageNum", "0")).andExpect(status().isBadRequest());
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM user_api_key WHERE user_id=(SELECT id FROM app_user WHERE username='mcp_viewer')", Long.class);
        mvc.perform(post("/settings/api-keys").with(user("mcp_viewer").roles("VIEWER")).with(csrf())
                        .param("name", "empty scopes").param("validDays", "90").param("serviceSlugs", "mcp-published"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("请检查")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_api_key WHERE user_id=(SELECT id FROM app_user WHERE username='mcp_viewer')", Long.class)).isEqualTo(before);
    }

    @Test void transportAndAuditNeverAcceptOrPersistSecretsInWrongPlaces() throws Exception {
        var issued = keys.create("mcp_viewer", "audit", 90, Set.of(UserApiKeyService.READ, UserApiKeyService.QUERY), "mcp-published", "");
        String bearer = "Bearer " + issued.token(); String marker = "mcp-secret-marker";
        mvc.perform(mcp(bearer, 1, "ping", "{}").header("Origin", "http://localhost")).andExpect(status().isOk());
        mvc.perform(mcp(bearer, 2, "ping", "{}").header("Origin", "https://example.invalid")).andExpect(status().isForbidden());
        mvc.perform(get("/mcp").header("Authorization", bearer)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/mcp").with(user("mcp_viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content(rpc(3, "ping", "{}"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").queryParam("api_key", issued.token()).contentType(MediaType.APPLICATION_JSON).content(rpc(4, "ping", "{}"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("Authorization", bearer).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(rpc(5, "ping", "{}"))).andExpect(status().isUnauthorized());
        mvc.perform(mcp(bearer, 6, "tools/call", "{\"name\":\"query_data_service\",\"arguments\":{\"slug\":\"mcp-published\",\"params\":{\"id\":\"1\",\"marker\":\"" + marker + "\"}}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE CONCAT(actor,action,target,outcome) LIKE ?", Integer.class, "%" + issued.token() + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE CONCAT(actor,action,target,outcome) LIKE ?", Integer.class, "%" + marker + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='service_call' AND column_name IN ('token','params','data','result')", Integer.class)).isZero();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcp(String bearer, int id, String method, String params) {
        return post("/mcp").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(rpc(id, method, params));
    }
    private String rpc(int id, String method, String params) { return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}"; }
}
