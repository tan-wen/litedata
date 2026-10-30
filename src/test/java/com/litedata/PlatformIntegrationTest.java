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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.litedata.data.SourceService;
import com.litedata.model.DataService;
import com.litedata.model.DataSource;
import com.litedata.model.Pipeline;
import com.litedata.model.PipelineRun;
import com.litedata.pipeline.PipelineService;
import com.litedata.service.DataApiService;
import com.litedata.store.PlatformMapper;
import com.litedata.scheduling.DolphinMonitorClient;
import static org.mockito.Mockito.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.*;
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
    @MockitoBean DolphinMonitorClient monitor;

    @BeforeEach
    void setUp() throws Exception {
        reset(monitor);
        try(var root=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()); var statement=root.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS warehouse");
            statement.execute("GRANT ALL ON warehouse.* TO 'platform'@'%'");
        }
        jdbc.execute("CREATE DATABASE IF NOT EXISTS warehouse");
        jdbc.execute("CREATE TABLE IF NOT EXISTS warehouse.api_fixture (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS source_orders (id BIGINT PRIMARY KEY, updated_at TIMESTAMP(6) NOT NULL, INDEX ix_source_orders_updated_at (updated_at))");
        jdbc.update("DELETE FROM api_credential");
        jdbc.update("DELETE FROM service_call");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM pipeline");
        jdbc.update("DELETE FROM data_model");
        jdbc.update("INSERT INTO data_model(name,table_name,subject_area,layer) VALUES('订单ODS','ods_orders','测试','ODS')");
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
        jdbc.update("DELETE FROM service_call");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM pipeline");
        jdbc.update("DELETE FROM data_service");
        jdbc.update("DELETE FROM data_source");
    }

    @Test
    void sourceFormsPersistAndRenderAllDatabaseTypesAndRejectMismatchedSelections() throws Exception {
        var urls=Map.of("MYSQL","jdbc:mysql://host/sales", "POSTGRESQL","jdbc:postgresql://host/sales?currentSchema=source_data",
                "SQLSERVER","jdbc:sqlserver://host:1433;databaseName=sales", "ORACLE","jdbc:oracle:thin:@//host:1521/service");
        for(var entry:urls.entrySet()) {
            mvc.perform(post("/sources").with(csrf()).with(user("developer").roles("DEVELOPER"))
                    .param("name","form-"+entry.getKey()).param("databaseType",entry.getKey()).param("jdbcUrl",entry.getValue())
                    .param("username","reader").param("password","test-password"))
                    .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/sources"));
            var saved=mapper.sources().stream().filter(s->s.getName().equals("form-"+entry.getKey())).findFirst().orElseThrow();
            assertThat(saved.getDatabaseType().name()).isEqualTo(entry.getKey());
            mvc.perform(get("/sources/"+saved.getId()+"/edit").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("value=\""+entry.getKey()+"\" selected=\"selected\"")));
        }
        mvc.perform(get("/sources").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SQL Server")));
        mvc.perform(post("/sources").with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","mismatched").param("databaseType","ORACLE").param("jdbcUrl",urls.get("POSTGRESQL"))
                .param("username","reader").param("password","test-password"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("所选数据库类型与 JDBC 地址不一致")));
        assertThat(mapper.sourceCount()).isEqualTo(4);
    }

    @Test
    void flywayCreatesPlatformTablesAndProtectedPagesRender() throws Exception {
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('data_source','pipeline','pipeline_run','data_service','api_credential','service_call')", Integer.class);
        assertThat(tables).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='data_service' AND column_name='max_rows'",Integer.class)).isZero();

        mvc.perform(get("/sources")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/login")));
        for (String page : new String[] {"/", "/sources", "/pipelines", "/services"}) {
            mvc.perform(get(page).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"));
        }
    }

    @Test void schedulerPagesReadRealInstanceIdentitiesAndProtectNodeLogs() throws Exception {
        sourceService.create("scheduler-source",MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        pipelineService.create("日志管道",mapper.sources().getFirst().getId(),"source_orders","updated_at","ods_orders","","0 0 * * * ?");
        long id=mapper.pipelines().getFirst().getId();
        mapper.definition(id,88); mapper.rememberWorkflow("pipeline",id,88);
        var workflow=new DolphinMonitorClient.Workflow(101,88,"调度测试实例","FAILURE","2026-10-24 13:00:00","2026-10-24 13:01:00",1);
        var node=new DolphinMonitorClient.Node(201,"同步节点","FAILURE","2026-10-24 13:00:01","2026-10-24 13:00:59",2);
        when(monitor.instances(eq(88L),any(),eq(""))).thenReturn(new DolphinMonitorClient.Page(1,java.util.List.of(workflow)));
        when(monitor.instance(101)).thenReturn(workflow); when(monitor.nodes(101)).thenReturn(java.util.List.of(node));
        when(monitor.log(201,0,200)).thenReturn("INFO start\npassword=must-stay-private\n<script>alert(1)</script>\n");
        String path="/scheduling/pipeline/"+id;
        mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("节点 / 日志")));
        mvc.perform(get(path+"/instances/101").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-log-console")));
        mvc.perform(get(path+"/instances/101").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-log-console"))));
        String log=path+"/instances/101/tasks/201/log";
        mvc.perform(get(log).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get(log).with(user("developer").roles("DEVELOPER"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.nextOffset").value(3))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must-stay-private"))));
        mvc.perform(get(path+"/instances/101/tasks/999/log").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get(log).param("offset","-1").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("workflowCode","999").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        when(monitor.instance(102)).thenReturn(new DolphinMonitorClient.Workflow(102,999,"foreign","SUCCESS","","",1));
        mvc.perform(get(path+"/instances/102").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        verify(monitor,never()).log(eq(999L),anyInt(),anyInt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='SCHEDULER_LOG_READ'",Long.class)).isEqualTo(1L);
    }

    @Test void schedulerPaginationPreservesFiltersAndEngineOutagesKeepThePageUsable() throws Exception {
        sourceService.create("scheduler-page-source",MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        pipelineService.create("分页管道",mapper.sources().getFirst().getId(),"source_orders","updated_at","ods_orders","","0 0 * * * ?");
        long id=mapper.pipelines().getFirst().getId(); mapper.definition(id,89);
        var item=new DolphinMonitorClient.Workflow(103,89,"test","SUCCESS","","",1);
        when(monitor.instances(eq(89L),any(),eq("SUCCESS"))).thenReturn(new DolphinMonitorClient.Page(21,java.util.List.of(item)));
        String path="/scheduling/pipeline/"+id;
        mvc.perform(get(path).param("stateType","SUCCESS").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stateType=SUCCESS")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("workflowCode=89")));
        when(monitor.instances(eq(89L),any(),eq(""))).thenThrow(new IllegalStateException("调度引擎暂不可用"));
        mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("历史未关联回调")));
        mvc.perform(get("/scheduling/preview").param("cron","0 0 * * * ?").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("Asia/Shanghai")).andExpect(jsonPath("$.nextTimes.length()").value(3));
        mvc.perform(get("/scheduling/preview").param("cron","* * * * * *").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    @Test void schedulerStatusTracksCompletionRetriesAndLateNodesWithoutExposingOtherResources() throws Exception {
        sourceService.create("status-source",MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        pipelineService.create("状态管道",mapper.sources().getFirst().getId(),"source_orders","updated_at","ods_orders","","0 0 * * * ?");
        long id=mapper.pipelines().getFirst().getId(); mapper.definition(id,88);
        var running=new DolphinMonitorClient.Workflow(401,88,"status-fixture","RUNNING_EXECUTION","2026-10-24 22:00:00","",1);
        var success=new DolphinMonitorClient.Workflow(401,88,"status-fixture","SUCCESS","2026-10-24 22:00:00","2026-10-24 22:01:00",1);
        var activeNode=new DolphinMonitorClient.Node(501,"同步节点","RUNNING_EXECUTION","2026-10-24 22:00:01","",0);
        var doneNode=new DolphinMonitorClient.Node(501,"同步节点","SUCCESS","2026-10-24 22:00:01","2026-10-24 22:00:59",1);
        String path="/scheduling/pipeline/"+id+"/instances/401";
        when(monitor.instance(401)).thenReturn(running); when(monitor.nodes(401)).thenReturn(java.util.List.of(activeNode));
        mvc.perform(get(path+"/status").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.finished").value(false)).andExpect(jsonPath("$.instance.state").value("RUNNING_EXECUTION"))
                .andExpect(jsonPath("$.nodes[0].id").value(501)).andExpect(jsonPath("$.updatedAt").isNotEmpty());
        when(monitor.nodes(401)).thenReturn(java.util.List.of(new DolphinMonitorClient.Node(501,"同步节点","FAILURE","","",0)));
        mvc.perform(get(path+"/status").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(false));
        when(monitor.instance(401)).thenReturn(success); when(monitor.nodes(401)).thenReturn(java.util.List.of(activeNode));
        mvc.perform(get(path+"/status").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(false));
        when(monitor.nodes(401)).thenReturn(java.util.List.of(doneNode));
        mvc.perform(get(path+"/status").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(true)).andExpect(jsonPath("$.nodes[0].retryTimes").value(1))
                .andExpect(jsonPath("$.labels.SUCCESS").value("成功"));
        when(monitor.instance(401)).thenReturn(running); when(monitor.nodes(401)).thenReturn(java.util.List.of());
        mvc.perform(get(path+"/status").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(false)).andExpect(jsonPath("$.nodes").isEmpty());
        mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("等待节点派发")));
        when(monitor.nodes(401)).thenThrow(new IllegalStateException("调度引擎暂不可用"));
        mvc.perform(get(path+"/status").with(user("admin").roles("ADMIN"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("调度引擎暂不可用"));
        when(monitor.instance(402)).thenReturn(new DolphinMonitorClient.Workflow(402,999,"foreign","SUCCESS","","",1));
        mvc.perform(get("/scheduling/pipeline/"+id+"/instances/402/status").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        verify(monitor,never()).nodes(402);
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
        apiService.create("客户查询", "customers", "SELECT id, name FROM warehouse.api_fixture WHERE id = :customerId");
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
        assertThat(jdbc.queryForList("SELECT status_code FROM service_call ORDER BY id",Integer.class))
                .containsExactly(404,401,200,401,200);
        assertThat(apiService.list().getFirst().getDailyCalls()).isEqualTo(5);
    }

    @Test
    void servicesAreCreatedWithoutRowLimitsAndQueriesUseFixedPagination() throws Exception {
        mvc.perform(post("/services").with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("name","分页查询").param("slug","pages")
                .param("querySql","SELECT id, name FROM warehouse.api_fixture WHERE id>=:minId ORDER BY id"))
                .andExpect(status().is3xxRedirection());
        var service=mapper.services().getFirst(); apiService.publish(service.getId());
        String key=apiService.credential(service.getId());
        jdbc.update("INSERT INTO warehouse.api_fixture VALUES (2,'Grace'),(3,'Linus')");
        mvc.perform(get("/api/data/pages").header("X-API-Key",key).param("minId","1").param("pageNum","1").param("pageSize","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.pageNum").value(1)).andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[1].id").value(2)).andExpect(jsonPath("$.truncated").doesNotExist());
        mvc.perform(get("/api/data/pages").header("X-API-Key",key).param("minId","1").param("pageNum","2").param("pageSize","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.hasMore").value(false)).andExpect(jsonPath("$.data[0].id").value(3));
        mvc.perform(get("/api/data/pages").header("X-API-Key",key).param("minId","1").param("pageNum","3").param("pageSize","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/data/pages").header("X-API-Key",key).param("minId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageNum").value(1))
                .andExpect(jsonPath("$.pageSize").value(100)).andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/data/pages").header("X-API-Key",key).param("minId","1").param("pageNum","2147483647").param("pageSize","1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/services").with(user("reader").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("日调用量")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("最大返回行数"))));
        assertThat(jdbc.queryForList("SELECT row_count FROM service_call ORDER BY id",Integer.class)).containsExactly(2,1,0,3,0);
        assertThat(jdbc.queryForObject("SELECT MIN(duration_ms) FROM service_call",Long.class)).isNotNegative();
    }

    @Test
    void invalidParametersAndQueryFailuresAreRecordedWithoutRequestSecrets() throws Exception {
        apiService.create("验证查询","validation","SELECT id FROM warehouse.api_fixture WHERE name=:name OR name=:name ORDER BY id");
        var service=mapper.services().getFirst(); apiService.publish(service.getId());
        String key=apiService.credential(service.getId());
        for(var invalid:java.util.List.of(Map.of("pageNum","0"),Map.of("pageNum","-1"),Map.of("pageNum","1.5"),
                Map.of("pageNum","2147483648"),Map.of("pageSize",""),Map.of("pageSize","0"),Map.of("pageSize","1001"),Map.of("pageSize","1;DROP TABLE data_service"))) {
            var request=get("/api/data/validation").header("X-API-Key",key).param("name","Ada");
            invalid.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/data/validation").header("X-API-Key",key)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/data/validation").header("X-API-Key",key).param("name","Ada").param("extra","unexpected"))
                .andExpect(status().isBadRequest());
        String sensitiveValue="private-input' OR '1'='1";
        mvc.perform(get("/api/data/validation").header("X-API-Key",key).param("name",sensitiveValue))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        jdbc.update("UPDATE data_service SET query_sql='SELECT id FROM warehouse.missing_api_fixture' WHERE id=?",service.getId());
        mvc.perform(get("/api/data/validation").header("X-API-Key",key))
                .andExpect(status().isBadGateway());
        assertThat(jdbc.queryForList("SELECT status_code FROM service_call ORDER BY id",Integer.class))
                .containsExactly(400,400,400,400,400,400,400,400,400,400,200,502);
        var records=jdbc.queryForList("SELECT * FROM service_call").toString();
        assertThat(records).doesNotContain(key,sensitiveValue);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse.api_fixture",Integer.class)).isEqualTo(1);
    }

    @Test
    void dailyCountsUseShanghaiDayBoundariesAndCallPagesAreProtectedAndPaged() throws Exception {
        apiService.create("日志查询","logs","SELECT id FROM warehouse.api_fixture ORDER BY id");
        var service=mapper.services().getFirst();
        apiService.create("未调用服务","unused","SELECT id FROM warehouse.api_fixture");
        LocalDateTime start=LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        for(var time:java.util.List.of(start.minusNanos(1000),start,start.plusHours(23),start.plusDays(1))) {
            jdbc.update("INSERT INTO service_call(service_id,started_at,duration_ms,status_code,row_count,page_num,page_size) VALUES(?,?,5,200,1,1,100)",service.getId(),time);
        }
        var counts=apiService.list().stream().collect(java.util.stream.Collectors.toMap(DataService::getSlug,DataService::getDailyCalls));
        assertThat(counts).containsEntry("logs",2L).containsEntry("unused",0L);
        String path="/services/"+service.getId()+"/calls";
        mvc.perform(get(path)).andExpect(status().is3xxRedirection());
        mvc.perform(get(path).with(user("reader").roles("VIEWER")).param("pageSize","2"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("调用记录")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("hasMore",true));
        mvc.perform(get(path).with(user("reader").roles("VIEWER")).param("pageSize","2").param("pageNum","2"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("hasMore",false));
        mvc.perform(get(path).with(user("reader").roles("VIEWER")).param("pageNum","0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/services/999999/calls").with(user("reader").roles("VIEWER")))
                .andExpect(status().isNotFound());
        var calls=apiService.calls(service.getId(),new com.litedata.service.QueryPage(1,20));
        assertThat(calls).hasSize(4);
        assertThat(calls.get(1).getStartedAt()).isEqualTo(start.plusHours(31));
        var empty=apiService.list().stream().filter(s->s.getSlug().equals("unused")).findFirst().orElseThrow();
        mvc.perform(get("/services/"+empty.getId()+"/calls").with(user("reader").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("暂无调用记录")));
    }

    @Test
    void claimHasSingleOwnerAndFinishPreservesWatermarkSemantics() {
        Pipeline pipeline = createPipeline();
        LocalDateTime originalWatermark = mapper.pipeline(pipeline.getId()).getWatermark();

        Map<String, Object> firstClaim = pipelineService.claim(pipeline.getId());
        assertThat(firstClaim).doesNotContainKey("transformSql");
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

    @Test
    void resourceListsAndFormsHaveSeparateRoutesAndEnforceRoles() throws Exception {
        var pipeline=createPipeline();
        apiService.create("独立表单服务","forms","SELECT id FROM warehouse.api_fixture");
        var ids=Map.of("sources",pipeline.getSourceId(),"pipelines",pipeline.getId(),
                "services",mapper.services().getFirst().getId(),"users",mapper.findUser("admin").getId());
        for(var resource:ids.keySet()) {
            String base="/"+resource;
            mvc.perform(get(base).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\""+base+"/new\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(base+"/"+ids.get(resource)+"/edit")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\""+(resource.equals("users")?"username":"name")+"\""))));
            for(String path:java.util.List.of(base+"/new",base+"/"+ids.get(resource)+"/edit")) {
                mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("取消")))
                        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\""+(resource.equals("users")?"username":"name")+"\"")));
                mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
                mvc.perform(get(path)).andExpect(status().is3xxRedirection());
                mvc.perform(get(path).with(user("developer").roles("DEVELOPER")))
                        .andExpect(resource.equals("users")?status().isForbidden():status().isOk());
            }
            mvc.perform(get(base+"/999999/edit").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
            mvc.perform(post(base+"/"+ids.get(resource)).with(csrf()).with(user("viewer").roles("VIEWER")))
                    .andExpect(status().isForbidden());
            mvc.perform(post(base+"/"+ids.get(resource)).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void sourceFormsSaveAndRetainSafeInputOnValidationFailure() throws Exception {
        mvc.perform(post("/sources").with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","form-source").param("jdbcUrl",MYSQL.getJdbcUrl())
                .param("username",MYSQL.getUsername()).param("password","source-form-secret"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/sources"));
        var source=mapper.sources().getFirst();
        String encrypted=source.getEncryptedPassword();
        mvc.perform(get("/sources/"+source.getId()+"/edit").with(user("developer").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("source-form-secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(encrypted))));
        mvc.perform(post("/sources/"+source.getId()).with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","source-renamed").param("jdbcUrl",MYSQL.getJdbcUrl()).param("username",MYSQL.getUsername())
                .param("password","").param("encryptedPassword","forged").param("status","CONNECTED"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/sources"));
        assertThat(mapper.source(source.getId()).getName()).isEqualTo("source-renamed");
        assertThat(mapper.source(source.getId()).getEncryptedPassword()).isEqualTo(encrypted);
        assertThat(mapper.source(source.getId()).getStatus()).isEqualTo("UNTESTED");
        mvc.perform(post("/sources").with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","source-renamed").param("jdbcUrl",MYSQL.getJdbcUrl())
                .param("username","retained-user").param("password","never-render-this-secret"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("source-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("retained-user")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("never-render-this-secret"))));
        assertThat(mapper.sources()).hasSize(1);
    }

    @Test
    void pipelineEditsKeepWatermarkAndCannotChangeRunningOrSourceBoundaries() throws Exception {
        var pipeline=createPipeline();
        long runId=(Long)pipelineService.claim(pipeline.getId()).get("runId");
        mvc.perform(pipelineEdit(pipeline).param("name","running-edit"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("已有执行中的实例")));
        assertThat(mapper.pipeline(pipeline.getId()).getName()).isEqualTo(pipeline.getName());
        pipelineService.finish(runId,true,"done");
        var watermark=mapper.pipeline(pipeline.getId()).getWatermark();
        mapper.definition(pipeline.getId(),12345L); mapper.rememberWorkflow("pipeline",pipeline.getId(),12345L); mapper.schedule(pipeline.getId(),42,"SCHEDULED");
        mvc.perform(pipelineEdit(pipeline).param("name","edited-pipeline").param("watermark","2099-01-01T00:00:00")
                .param("activeRunId","1").param("definitionCode","1"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/pipelines"));
        var edited=mapper.pipeline(pipeline.getId());
        assertThat(edited.getName()).isEqualTo("edited-pipeline");
        assertThat(edited.getWatermark()).isEqualTo(watermark);
        assertThat(edited.getDefinitionCode()).isEqualTo(12345L);
        assertThat(edited.getScheduleId()).isEqualTo(42);
        assertThat(edited.getActiveRunId()).isNull();
        var frequencyChanged=new Pipeline(); org.springframework.beans.BeanUtils.copyProperties(edited,frequencyChanged);
        frequencyChanged.setCronExpression("0 */5 * * * ?");
        mvc.perform(pipelineEdit(frequencyChanged).param("name","edited-pipeline"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("请先暂停后再修改执行周期")));
        assertThat(mapper.pipeline(pipeline.getId()).getCronExpression()).isEqualTo("0 0 * * * ?");
        mapper.schedule(pipeline.getId(),42,"PAUSED");
        mvc.perform(pipelineEdit(frequencyChanged).param("name","edited-pipeline"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/pipelines"));
        var rescheduled=mapper.pipeline(pipeline.getId());
        assertThat(rescheduled.getCronExpression()).isEqualTo("0 */5 * * * ?");
        assertThat(rescheduled.getWatermark()).isEqualTo(watermark);
        assertThat(rescheduled.getDefinitionCode()).isNull();
        assertThat(rescheduled.getScheduleId()).isNull();
        assertThat(rescheduled.getStatus()).isEqualTo("PAUSED");
        assertThat(mapper.workflowCodes("pipeline",pipeline.getId())).contains(12345L);
        var changed=new Pipeline(); org.springframework.beans.BeanUtils.copyProperties(pipeline,changed);
        changed.setTargetTable("other_target");
        mvc.perform(pipelineEdit(changed).param("name","bad-boundary")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("同步边界创建后固定")));
        assertThat(mapper.pipeline(pipeline.getId()).getTargetTable()).isEqualTo(pipeline.getTargetTable());
        mvc.perform(post("/pipelines").with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","invalid-source").param("sourceId","not-a-number"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("pipeline-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("invalid-source")));
    }

    @Test
    void serviceEditReturnsToListAsDraftAndRetainsApiIdentityAndCredentials() throws Exception {
        apiService.create("待编辑服务","edit-service","SELECT id FROM warehouse.api_fixture");
        var service=mapper.services().getFirst();
        apiService.publish(service.getId()); String token=apiService.credential(service.getId());
        mvc.perform(post("/services/"+service.getId()).with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","编辑后服务").param("slug",service.getSlug()).param("querySql","SELECT name FROM warehouse.api_fixture")
                .param("published","true"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/services"));
        var edited=mapper.dataService(service.getId());
        assertThat(edited.getName()).isEqualTo("编辑后服务"); assertThat(edited.isPublished()).isFalse();
        assertThat(edited.getQuerySql()).isEqualTo("SELECT name FROM warehouse.api_fixture");
        assertThat(mapper.credentialValid(service.getId(),DataApiService.hash(token))).isEqualTo(1);
        mvc.perform(post("/services/"+service.getId()).with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","retained-service").param("slug","changed-path").param("querySql","SELECT id FROM warehouse.api_fixture"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("service-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("retained-service")));
        assertThat(mapper.dataService(service.getId()).getSlug()).isEqualTo(service.getSlug());
        mvc.perform(post("/services").with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("name","invalid-sql").param("slug","invalid-sql").param("querySql","DELETE FROM warehouse.api_fixture"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("service-form"));
        assertThat(mapper.services()).hasSize(1);
    }

    @Test
    void userFormEditsPreservePasswordsAndPreventRemovingOwnAdminRole() throws Exception {
        mvc.perform(post("/users").with(csrf()).with(user("admin").roles("ADMIN"))
                .param("username","form-account").param("password","FormAccountPassword2026").param("role","VIEWER"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/users"));
        try {
            var account=mapper.findUser("form-account");
            mvc.perform(get("/users/"+account.getId()+"/edit").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(account.getPasswordHash()))));
            mvc.perform(post("/users/"+account.getId()).with(csrf()).with(user("admin").roles("ADMIN"))
                    .param("username",account.getUsername()).param("password","").param("role","DEVELOPER").param("enabled","false"))
                    .andExpect(status().is3xxRedirection()).andExpect(header().string("Location","/users"));
            assertThat(mapper.user(account.getId()).getRole()).isEqualTo("DEVELOPER");
            assertThat(mapper.user(account.getId()).getPasswordHash()).isEqualTo(account.getPasswordHash());
            assertThat(mapper.user(account.getId()).isEnabled()).isTrue();
            mvc.perform(post("/users").with(csrf()).with(user("admin").roles("ADMIN"))
                    .param("username","form-account").param("password","DuplicateSecret2026").param("role","ADMIN"))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("user-form"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("用户名已存在")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("DuplicateSecret2026"))));
            var admin=mapper.findUser("admin");
            mvc.perform(post("/users/"+admin.getId()).with(csrf()).with(user("admin").roles("ADMIN"))
                    .param("username","admin").param("password","").param("role","VIEWER"))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("不能移除当前登录账号的管理员角色")));
            assertThat(mapper.findUser("admin").getRole()).isEqualTo("ADMIN");
        } finally { jdbc.update("DELETE FROM app_user WHERE username='form-account'"); }
    }

    @Test
    void allResourceListsPageInTheDatabaseAndPreserveModelFilters() throws Exception {
        try {
            for(String path:java.util.List.of("/sources","/pipelines","/services","/models","/transforms")) {
                mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("totalCount",path.equals("/models")?1L:0L));
            }
            for(int i=1;i<=3;i++) {
                sourceService.create("paged-source-"+i,MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
                pipelineService.create("paged-pipeline-"+i,mapper.sources().getFirst().getId(),"source_orders","updated_at","ods_orders","","0 0 * * * ?");
                apiService.create("paged-service-"+i,"paged-service-"+i,"SELECT id FROM warehouse.api_fixture");
                mapper.insertUser("paged-user-"+i,"unused-test-hash","VIEWER");
                jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer) VALUES(?,?,?,?)",
                        "page_model_"+i,i==1?"Literal_%":"Paged Model "+i,"分页域",i==3?"ADS":"DWD");
                long modelId=jdbc.queryForObject("SELECT id FROM data_model WHERE table_name=?",Long.class,"page_model_"+i);
                jdbc.update("INSERT INTO sql_task(name,model_id,sql_text) VALUES(?,?,?)","paged-task-"+i,modelId,"INSERT INTO warehouse.page_model_"+i+" SELECT id FROM warehouse.api_fixture");
            }
            var tables=Map.of("sources","data_source","pipelines","pipeline","services","data_service",
                    "users","app_user","models","data_model","transforms","sql_task");
            var attributes=Map.of("sources","sources","pipelines","pipelines","services","services",
                    "users","users","models","models","transforms","tasks");
            for(var resource:tables.keySet()) {
                var ids=jdbc.queryForList("SELECT id FROM "+tables.get(resource)+" ORDER BY id DESC",Long.class);
                for(int number:java.util.List.of(1,2,Integer.MAX_VALUE)) {
                    int actual=Math.min(number,(ids.size()+1)/2);
                    var result=mvc.perform(get("/"+resource).with(user("admin").roles("ADMIN"))
                            .param("pageNum",Integer.toString(number)).param("pageSize","2"))
                            .andExpect(status().isOk())
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("pageNum",actual))
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("totalCount",(long)ids.size()))
                            .andReturn();
                    var rows=(java.util.List<?>)result.getModelAndView().getModel().get(attributes.get(resource));
                    var actualIds=rows.stream().map(row->(Long)new org.springframework.beans.BeanWrapperImpl(row).getPropertyValue("id")).toList();
                    assertThat(actualIds).containsExactlyElementsOf(ids.subList((actual-1)*2,Math.min(actual*2,ids.size())));
                }
                for(var params:java.util.List.of(Map.of("pageNum","0"),Map.of("pageNum","-1"),Map.of("pageNum","x"),
                        Map.of("pageSize","0"),Map.of("pageSize","1001"),Map.of("pageNum","2147483648"))) {
                    var request=get("/"+resource).with(user("admin").roles("ADMIN")); params.forEach(request::param);
                    mvc.perform(request).andExpect(status().isBadRequest());
                }
            }
            mvc.perform(get("/models").with(user("admin").roles("ADMIN")).param("keyword","PAGE_MODEL").param("layer","DWD").param("pageSize","1"))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("totalCount",2L))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("keyword=PAGE_MODEL")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("layer=DWD")));
            mvc.perform(get("/models").with(user("admin").roles("ADMIN")).param("keyword","literal_%"))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("totalCount",1L));
            jdbc.update("UPDATE sql_task SET archived=TRUE WHERE name='paged-task-3'");
            jdbc.update("UPDATE data_model SET archived=TRUE WHERE table_name='page_model_3'");
            for(String path:java.util.List.of("/models","/transforms"))
                mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("totalCount",path.equals("/models")?3L:2L));
            mvc.perform(get("/pipelines/new").with(user("developer").roles("DEVELOPER")))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attribute("sources",org.hamcrest.Matchers.hasSize(3)));
        } finally {
            jdbc.update("DELETE FROM sql_task WHERE name LIKE 'paged-task-%'");
            jdbc.update("DELETE FROM data_model WHERE table_name LIKE 'page_model_%'");
            jdbc.update("DELETE FROM app_user WHERE username LIKE 'paged-user-%'");
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pipelineEdit(Pipeline pipeline) {
        return post("/pipelines/"+pipeline.getId()).with(csrf()).with(user("developer").roles("DEVELOPER"))
                .param("sourceId",Long.toString(pipeline.getSourceId())).param("sourceTable",pipeline.getSourceTable())
                .param("watermarkColumn",pipeline.getWatermarkColumn()).param("targetTable",pipeline.getTargetTable())
                .param("transformSql",pipeline.getTransformSql()).param("cronExpression",pipeline.getCronExpression());
    }

    @Test
    void syncRecordsAreScopedPaginatedAndReadableWithoutExposingEngineLogs() throws Exception {
        var pipeline=createPipeline();
        pipelineService.create("other-record-task",pipeline.getSourceId(),"source_orders","updated_at","ods_orders","","0 0 * * * ?");
        long other=mapper.pipelines().getFirst().getId();
        pipelineService.create("empty-record-task",pipeline.getSourceId(),"source_orders","updated_at","ods_orders","","0 0 * * * ?");
        long empty=mapper.pipelines().getFirst().getId();
        var sourceLower=LocalDateTime.of(2026,1,1,0,0,0,123456000);
        var started=LocalDateTime.of(2026,1,2,0,0);
        for(int i=0;i<3;i++) jdbc.update("INSERT INTO pipeline_run(pipeline_id,status,started_at,finished_at,lower_bound,upper_bound,message) VALUES(?,'SUCCESS',?,?,?,?,?)",
                pipeline.getId(),started.plusHours(i),started.plusHours(i).plusMinutes(1),sourceLower,sourceLower.plusDays(1),i==2?"<img src=x onerror=alert(1)>":"owned-record-"+i);
        jdbc.update("INSERT INTO pipeline_run(pipeline_id,status,started_at,lower_bound,upper_bound,message) VALUES(?,'RUNNING',?,?,?,'foreign-record')",
                other,started.plusDays(1),sourceLower,sourceLower.plusDays(1));
        String path="/pipelines/"+pipeline.getId()+"/runs";
        var result=mvc.perform(get(path).with(user("viewer").roles("VIEWER")).param("pageSize","2"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("历史未关联回调")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("foreign-record"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;img src=x onerror=alert(1)&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-01-01 00:00:00.123456")))
                .andReturn();
        var view=result.getModelAndView().getModel();
        assertThat(view.get("totalCount")).isEqualTo(3L); assertThat(view.get("pagePath")).isEqualTo(path);
        @SuppressWarnings("unchecked") var rows=(java.util.List<PipelineRun>)view.get("runs");
        assertThat(rows).hasSize(2).allMatch(run->run.getPipelineId().equals(pipeline.getId()));
        assertThat(rows.getFirst().getStartedAt()).isEqualTo(started.plusHours(10));
        assertThat(rows.getFirst().getLowerBound()).isEqualTo(sourceLower);
        assertThat(rows.getFirst().getUpperBound()).isEqualTo(sourceLower.plusDays(1));
        var last=mvc.perform(get(path).with(user("viewer").roles("VIEWER")).param("pageSize","2").param("pageNum","999"))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        assertThat(last.get("pageNum")).isEqualTo(2);
        assertThat(((java.util.List<?>)last.get("runs"))).hasSize(1);
        mvc.perform(get(path).with(user("viewer").roles("VIEWER")).param("pageSize","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/pipelines/"+empty+"/runs").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("没有历史未关联回调")));
        mvc.perform(get("/pipelines/0/runs").with(user("viewer").roles("VIEWER"))).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/pipelines").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/scheduling/pipeline/"+pipeline.getId())))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(path))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("平台近期回调记录"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("owned-record"))));
    }

    @Test
    void breadcrumbsUseResolvedViewsOnFailedPostsAndEscapeResourceLabels() throws Exception {
        var pipeline=createPipeline();
        String label="订单 <img src=x onerror=alert(1)>";
        jdbc.update("UPDATE pipeline SET name=? WHERE id=?",label,pipeline.getId());
        var records=mvc.perform(get("/pipelines/"+pipeline.getId()+"/runs").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"面包屑\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<img src=x"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;img src=x onerror=alert(1)&gt;")))
                .andReturn();
        @SuppressWarnings("unchecked") var crumbs=(java.util.List<com.litedata.web.PageBreadcrumbs.Item>)records.getModelAndView().getModel().get("breadcrumbs");
        assertThat(crumbs).extracting(com.litedata.web.PageBreadcrumbs.Item::href)
                .containsExactly("/","/pipelines","/pipelines#pipeline-"+pipeline.getId(),"/scheduling/pipeline/"+pipeline.getId(),null);
        var failed=mvc.perform(pipelineEdit(pipeline).param("name",label).param("cronExpression","invalid"))
                .andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var editing=(java.util.List<com.litedata.web.PageBreadcrumbs.Item>)failed.getModelAndView().getModel().get("breadcrumbs");
        assertThat(editing.getLast().label()).isEqualTo("编辑同步管道");
        assertThat(editing.get(2).href()).isEqualTo("/pipelines#pipeline-"+pipeline.getId());
        mvc.perform(get("/sources/new").with(user("developer").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("aria-current=\"page\">新增数据源")));
    }

    @Test void workerIdentityPersistsAcrossFailureRetryAndIdempotentCallbacks() throws Exception {
        var pipeline=createPipeline(); String claim="/internal/pipelines/"+pipeline.getId()+"/claim";
        mapper.definition(pipeline.getId(),88);
        when(monitor.workflowForTask(501)).thenReturn(401L);
        when(monitor.instance(401)).thenReturn(new DolphinMonitorClient.Workflow(401,88,"bound","RUNNING_EXECUTION","","",1));
        String token="integration-test-internal-token-0123456789";
        mvc.perform(post(claim).contentType("application/json").content("{\"workflowInstanceId\":401,\"taskInstanceId\":501}"))
                .andExpect(status().isUnauthorized());
        for(String body:java.util.List.of("{\"workflowInstanceId\":401}","{\"workflowInstanceId\":0,\"taskInstanceId\":501}"))
            mvc.perform(post(claim).header("X-Internal-Token",token).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(claim).header("X-Internal-Token",token).contentType("application/json")
                .content("{\"workflowInstanceId\":402,\"taskInstanceId\":501}")).andExpect(status().isBadRequest());
        when(monitor.workflowForTask(599)).thenReturn(999L);
        when(monitor.instance(999)).thenReturn(new DolphinMonitorClient.Workflow(999,999,"foreign","RUNNING_EXECUTION","","",1));
        mvc.perform(post(claim).header("X-Internal-Token",token).contentType("application/json")
                .content("{\"taskInstanceId\":599}")).andExpect(status().isNotFound());
        mvc.perform(post(claim).header("X-Internal-Token",token).contentType("application/json")
                .content("{\"workflowInstanceId\":401,\"taskInstanceId\":501}")).andExpect(status().isOk());
        long first=mapper.pipeline(pipeline.getId()).getActiveRunId();
        assertThat(mapper.run(first).getWorkflowInstanceId()).isEqualTo(401L);
        assertThat(mapper.run(first).getTaskInstanceId()).isEqualTo(501L);
        var before=mapper.pipeline(pipeline.getId()).getWatermark();
        pipelineService.finish(first,false,"");
        assertThat(mapper.pipeline(pipeline.getId()).getWatermark()).isEqualTo(before);
        mvc.perform(post(claim).header("X-Internal-Token",token).contentType("application/json")
                .content("{\"taskInstanceId\":501}")).andExpect(status().isOk());
        long retry=mapper.pipeline(pipeline.getId()).getActiveRunId();
        pipelineService.finish(retry,true,""); pipelineService.finish(retry,false,"");
        assertThat(mapper.instanceRunCount(pipeline.getId(),401)).isEqualTo(2);
        assertThat(mapper.run(retry).getStatus()).isEqualTo("SUCCESS");
        assertThat(mapper.pipelineRunCount(pipeline.getId())).isZero();
    }

    @Test void instanceCallbacksShowAllAttemptsWithoutPagingAndRefreshIndependentlyFromEngineSuccess() throws Exception {
        var pipeline=createPipeline(); long id=pipeline.getId(); mapper.definition(id,88);
        pipelineService.create("other-callback-task",pipeline.getSourceId(),"source_orders","updated_at","ods_orders","","0 0 * * * ?");
        long other=mapper.pipelines().getFirst().getId();
        var begin=LocalDateTime.of(2026,1,1,0,0,0,123456000);
        for(int i=0;i<3;i++) jdbc.update("INSERT INTO pipeline_run(pipeline_id,workflow_instance_id,task_instance_id,status,started_at,lower_bound,upper_bound,message) VALUES(?,401,501,?,?,?,?,?)",
                id,i==2?"RUNNING":"FAILED",begin.plusMinutes(i),begin,begin.plusDays(1),i==2?"<script>bad()</script>":"attempt-"+i);
        for(long owner:java.util.List.of(id,other)) jdbc.update("INSERT INTO pipeline_run(pipeline_id,workflow_instance_id,task_instance_id,status,lower_bound,upper_bound,message) VALUES(?,?,501,'SUCCESS',?,?,'must-not-leak')",
                owner,owner==id?402:401,begin,begin.plusDays(1));
        jdbc.update("INSERT INTO pipeline_run(pipeline_id,status,lower_bound,upper_bound,message) VALUES(?,'SUCCESS',?,?,'unlinked-history')",id,begin,begin.plusDays(1));
        var workflow=new DolphinMonitorClient.Workflow(401,88,"instance","SUCCESS","","",1);
        when(monitor.instance(401)).thenReturn(workflow); when(monitor.nodes(401)).thenReturn(java.util.List.of());
        String path="/scheduling/pipeline/"+id+"/instances/401";
        var page=mvc.perform(get(path).param("pageSize","2").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("同步回调记录")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;bad()&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must-not-leak"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unlinked-history"))))
                .andReturn().getModelAndView().getModel();
        var callbacks=(com.litedata.scheduling.PipelineCallbackRecords.Page)page.get("callbackPage");
        assertThat(callbacks.total()).isEqualTo(3L);
        assertThat(callbacks.items()).hasSize(3);
        assertThat(page).doesNotContainKeys("pageNum","pageSize","totalPages");
        mvc.perform(get(path+"/status").param("pageSize","2").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.callbackPage.total").value(3))
                .andExpect(jsonPath("$.callbackPage.items.length()").value(3))
                .andExpect(jsonPath("$.callbackPage.items[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.callbackPage.items[0].startedAt").value("2026-01-01 08:02:00.123456"))
                .andExpect(jsonPath("$.callbackPage.items[0].lowerBound").value("2026-01-01 00:00:00.123456"));
        mvc.perform(get(path).param("pageSize","2").param("pageNum","999").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("attempt-0")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("资源列表分页"))));
        when(monitor.instance(402)).thenReturn(new DolphinMonitorClient.Workflow(402,999,"foreign","SUCCESS","","",1));
        mvc.perform(get("/scheduling/pipeline/"+id+"/instances/402").with(user("viewer").roles("VIEWER"))).andExpect(status().isNotFound());
        mvc.perform(get("/pipelines/"+id+"/runs").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("unlinked-history")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("attempt-0"))));
        when(monitor.instance(401)).thenThrow(new IllegalStateException("调度引擎暂不可用"));
        mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("attempt-0")));
    }

    private Pipeline createPipeline() {
        sourceService.create("source-for-pipeline", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        DataSource source = mapper.sources().getFirst();
        pipelineService.create("orders-hourly", source.getId(), "source_orders", "updated_at", "ods_orders",
                "", "0 0 * * * ?");
        return mapper.pipelines().getFirst();
    }
}
