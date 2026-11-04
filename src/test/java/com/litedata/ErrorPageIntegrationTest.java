package com.litedata;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Keeps browser error pages safe while leaving machine-readable error responses intact. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ErrorPageIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4").withDatabaseName("platform").withUsername("platform").withPassword("error-page-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername); r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("platform.encryption-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="); r.add("platform.internal-token",()->"error-page-test-internal-token-0123456789"); r.add("platform.admin-password",()->"ErrorPageAdminPassword2026");
        r.add("platform.doris.jdbc-url",MYSQL::getJdbcUrl); r.add("platform.doris.username",MYSQL::getUsername); r.add("platform.doris.password",MYSQL::getPassword); r.add("spring.thymeleaf.cache",()->"false");
    }
    @Autowired MockMvc mvc;

    @Test void htmlErrorsUseSafeToastSeedsAndKeepTheirHttpStatus() throws Exception {
        mvc.perform(error(404))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("data-platform-notification=\"warning\"")))
                .andExpect(content().string(containsString("data-notification-status=\"404\"")))
                .andExpect(content().string(containsString("页面不存在")))
                .andExpect(content().string(containsString("返回工作台")));
        mvc.perform(error(403))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("无权访问")))
                .andExpect(content().string(containsString("当前账号没有访问此内容的权限")));
        mvc.perform(error(500).requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("password=private; SELECT internal_data")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("data-platform-notification=\"danger\"")))
                .andExpect(content().string(containsString("服务暂时无法处理该请求")))
                .andExpect(content().string(not(containsString("password=private"))))
                .andExpect(content().string(not(containsString("internal_data"))));
    }

    @Test void jsonErrorsKeepProblemDetailAndHttpStatus() throws Exception {
        mvc.perform(error(404).accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test void workflowHtmlErrorsUseTheSharedErrorPageWhileJsonEndpointsKeepTheirContract() throws Exception {
        mvc.perform(get("/workflows/999999").with(user("dev").roles("DEVELOPER")).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("data-platform-notification=\"warning\"")))
                .andExpect(content().string(containsString("页面不存在")));
        mvc.perform(get("/workflows/999999/graph").with(user("dev").roles("DEVELOPER")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("工作流不存在"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder error(int status) {
        return get("/error").with(user("dev").roles("DEVELOPER"))
                .accept(MediaType.TEXT_HTML)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/missing");
    }
}
