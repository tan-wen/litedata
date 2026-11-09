package com.litedata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkspaceLayoutIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("platform");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token", () -> "workspace-test-internal-token-0123456789");
        r.add("platform.admin-password", () -> "WorkspaceTestPassword2026");
        r.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        r.add("platform.doris.username", MYSQL::getUsername);
        r.add("platform.doris.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @Test
    void primaryPagesHaveOneBusinessTopbarWithoutGlobalSettingsOrBackLinks() throws Exception {
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES ('viewer',?,'VIEWER')", passwords.encode("WorkspaceViewerTest2026"));
        for (String path : List.of("/sources", "/models", "/pipelines", "/workflows", "/services", "/assets", "/metadata", "/metrics", "/quality", "/settings/api-keys")) {
            String html = mvc.perform(get(path).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var headers = Pattern.compile("<header class=\"page-topbar\">(.*?)</header>", Pattern.DOTALL).matcher(html);
            assertThat(headers.find()).as(path).isTrue();
            assertThat(headers.group(1)).contains("page-title", "breadcrumb-nav")
                .doesNotContain("workspace-back", "breadcrumb-back", "data-theme-mode", "/logout", "environment");
            assertThat(headers.find()).as(path).isFalse();
            assertThat(html).doesNotContain("class=\"topbar\"", "platform-card")
                .contains("sidebar-user", "href=\"/settings\"", "action=\"/logout\"", "name=\"_csrf\"");
        }
    }

    @Test
    void formsOfferTwoMatchingBackLinksAndKeepTheirCancelDestination() throws Exception {
        var result = mvc.perform(get("/services/new").param("pageSize", "50").param("pageNum", "2")
                .with(user("developer").roles("DEVELOPER")))
            .andExpect(status().isOk()).andReturn();
        var back = (com.litedata.web.PageBreadcrumbs.Item) result.getModelAndView().getModel().get("pageBack");
        assertThat(back.href()).isEqualTo("/services?pageNum=2&pageSize=50");
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("workspace-back");
        assertThat(Pattern.compile("href=\"/services\\?pageNum=2&amp;pageSize=50\"").matcher(html).results().count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void workflowCanvasKeepsHistoryBeforeTheSharedReturnAction() throws Exception {
        jdbc.update("INSERT INTO processing_workflow(name) VALUES ('工作区布局测试')");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM processing_workflow", Long.class);
        var result = mvc.perform(get("/workflows/" + id).with(user("developer").roles("DEVELOPER")))
            .andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();
        var header = Pattern.compile("<header class=\"page-topbar\">(.*?)</header>", Pattern.DOTALL).matcher(html);
        assertThat(header.find()).isTrue();
        assertThat(header.group(1)).contains("运行记录", "workspace-back", "href=\"/workflows\"");
        assertThat(header.group(1).indexOf("workspace-back")).isGreaterThan(header.group(1).indexOf("运行记录"));
        var back = (com.litedata.web.PageBreadcrumbs.Item) result.getModelAndView().getModel().get("pageBack");
        assertThat(back.href()).isEqualTo("/workflows");
    }

    @Test
    void settingsAreAvailableToEveryRoleWhileAccountAdministrationRemainsRestricted() throws Exception {
        String viewer = mvc.perform(get("/settings").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewer).contains("外观与主题", "data-theme-mode", "data-theme-accent", "只读用户", "单机开发环境")
            .doesNotContain("href=\"/users\"", "workspace-back");
        mvc.perform(get("/users").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        String admin = mvc.perform(get("/settings").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(admin).contains("管理账号权限", "管理员");
        mvc.perform(get("/settings")).andExpect(status().is3xxRedirection());
    }
}
