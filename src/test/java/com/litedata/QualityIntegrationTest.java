package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.quality.*;
import com.litedata.store.QualityMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Exercises V6 with the same MySQL JDBC protocol used for the Doris-compatible warehouse. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class QualityIntegrationTest {
    private static final ZoneId TZ = QualityValidation.ZONE;

    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("quality-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.flyway.clean-disabled", () -> "false");
        r.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        r.add("platform.doris.username", MYSQL::getUsername);
        r.add("platform.doris.password", MYSQL::getPassword);
        r.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token", () -> "quality-test-internal-token-0123456789");
        r.add("platform.admin-password", () -> "QualityAdminPassword2026");
        r.add("platform.quality.scheduling-enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired QualityService service;
    @Autowired QualityMapper mapper;
    @Autowired Flyway flyway;

    @BeforeEach
    void clean() {
        flyway.clean();
        flyway.migrate();

        jdbc.execute("CREATE TABLE quality_reference(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO quality_reference VALUES(1)");
        jdbc.execute("CREATE TABLE quality_fixture(id BIGINT, code VARCHAR(20), amount DECIMAL(12,2), note VARCHAR(30), category VARCHAR(20), ref_id BIGINT, occurred_at DATETIME, ends_at INT)");
        jdbc.update("INSERT INTO quality_fixture VALUES(1,'A',10,'ok','good',1,?,?)", now().minusHours(1), 10);
        jdbc.update("INSERT INTO quality_fixture VALUES(1,'',-2,'too-long-note','bad',99,?,?)", now().plusHours(2), 1);
        jdbc.execute("CREATE TABLE quality_empty(occurred_at DATETIME)");
        jdbc.execute("CREATE TABLE quality_window(occurred_at DATETIME)");
        jdbc.update("INSERT INTO quality_window VALUES(?)", now().minusHours(2));
        jdbc.update("INSERT INTO quality_window VALUES(?)", now().minusMinutes(30));
        jdbc.update("INSERT INTO quality_window VALUES(?)", now().plusHours(2));
    }

    @Test
    void persistsV6CrudVersionsPagesAndTemplates() throws Exception {
        long id = service.save(null, task("first", List.of(rule("nulls", RuleType.NOT_NULL, "code", "", ""))), "dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quality_task", Integer.class)).isEqualTo(1);
        var saved = service.require(id); assertThat(saved.getVersion()).isEqualTo(1); assertThat(saved.getRules()).hasSize(1);
        saved.setName("renamed"); saved.setRules(List.of(rule("positive", RuleType.POSITIVE, "amount", "", "")));
        service.save(id, saved, "dev"); assertThat(service.require(id).getVersion()).isEqualTo(2);
        var stale = service.require(id); stale.setVersion(1); assertThatIllegalArgumentException().isThrownBy(() -> service.save(id, stale, "dev")).withMessageContaining("变化");
        saved = service.require(id); saved.setCronExpression("0 0 * * * *"); service.save(id, saved, "dev");
        service.schedule(id, 3, true, "dev"); assertThat(service.require(id)).satisfies(t -> { assertThat(t.isEnabled()).isTrue(); assertThat(t.getNextRunAt()).isNotNull(); });
        service.schedule(id, 4, false, "dev"); assertThat(service.require(id).isEnabled()).isFalse();
        service.save(null, task("second", List.of(rule("rows", RuleType.ROW_COUNT, "", "0", "9"))), "dev");
        for (String path : List.of("/quality", "/quality/new", "/quality/" + id, "/quality/" + id + "/edit"))
            mvc.perform(get(path).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/quality").param("pageSize", "1").with(user("dev").roles("DEVELOPER"))).andExpect(model().attribute("totalCount", 2L)).andExpect(model().attribute("hasMore", true));
        mvc.perform(get("/quality").param("pageNum", "0").with(user("dev").roles("DEVELOPER"))).andExpect(status().isBadRequest());
        mvc.perform(get("/quality").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("新增质量任务"))));
        mvc.perform(get("/quality/new").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/quality/" + id + "/edit").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/quality").with(user("viewer").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/quality/" + id + "/run").param("version", "2").with(user("dev").roles("DEVELOPER"))).andExpect(status().isForbidden());
        mvc.perform(post("/quality").with(user("dev").roles("DEVELOPER")).with(csrf()).param("name", "").param("tableName", "quality_fixture").param("owner", "dev"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("任务名称不能为空")));
    }

    @Test
    void executesAllMysqlCompatibleRulesAndPreservesSafeErrors() throws Exception {
        List<QualityRule> rules = new ArrayList<>(List.of(
                rule("not-null", RuleType.NOT_NULL, "code", "", ""), rule("not-blank", RuleType.NOT_BLANK, "code", "", ""),
                rule("unique", RuleType.UNIQUE, "id", "", ""), rule("range", RuleType.RANGE, "amount", "0", "100"),
                rule("length", RuleType.LENGTH, "note", "1", "5"), rule("regex", RuleType.REGEX, "code", "^[A-Z]+$", ""),
                rule("enum", RuleType.ENUM, "category", "good\nok", ""), rule("nonnegative", RuleType.NON_NEGATIVE, "amount", "", ""),
                rule("positive", RuleType.POSITIVE, "amount", "", ""), rule("future", RuleType.FUTURE_TIME, "occurred_at", "", ""),
                rule("rows", RuleType.ROW_COUNT, "", "1", "10"), rule("sum", RuleType.SUM_RANGE, "amount", "0", "100"),
                rule("avg", RuleType.AVG_RANGE, "amount", "0", "100"), rule("compare", RuleType.COMPARE_COLUMNS, "ends_at,id", ">", ""),
                reference(), rule("change", RuleType.ROW_COUNT_CHANGE, "", "10", "")
        ));
        long id = service.save(null, task("all-rules", rules), "dev");
        long run1 = service.start(id, 1, "dev"); waitFor(run1);
        var first = mapper.results(run1); assertThat(first).hasSize(16);
        Map<String, Long> counts = new HashMap<>(); for (var result : first) counts.put(result.getName(), result.getAnomalyCount());
        assertThat(counts).containsEntry("not-null", 0L).containsEntry("not-blank", 1L).containsEntry("unique", 2L).containsEntry("range", 1L)
                .containsEntry("length", 1L).containsEntry("regex", 1L).containsEntry("enum", 1L).containsEntry("nonnegative", 1L).containsEntry("positive", 1L)
                .containsEntry("future", 1L).containsEntry("rows", 0L).containsEntry("sum", 0L).containsEntry("avg", 0L).containsEntry("compare", 1L).containsEntry("reference", 1L).containsEntry("change", 1L);
        assertThat(first).filteredOn(r -> r.getType() == RuleType.ROW_COUNT_CHANGE).extracting(QualityResult::getStatus).containsExactly("WARNING");
        jdbc.update("INSERT INTO quality_fixture VALUES(2,'B',2,'ok','good',1,?,?)", now().minusMinutes(5), 4);
        long run2 = service.start(id, 1, "dev"); waitFor(run2);
        assertThat(mapper.results(run2)).filteredOn(r -> r.getType() == RuleType.ROW_COUNT_CHANGE).extracting(QualityResult::getStatus).containsExactly("FAILED");
        long invalid = service.save(null, task("bad-column", List.of(rule("missing", RuleType.RANGE, "missing_column", "0", "1"))), "dev");
        long invalidRun = service.start(invalid, 1, "dev"); waitFor(invalidRun);
        var error = mapper.results(invalidRun).getFirst(); assertThat(error.getStatus()).isEqualTo("ERROR"); assertThat(error.getMessage()).doesNotContain("password", "jdbc", "missing_column");
        writePreview("form.html", mvc.perform(get("/quality/new").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        writePreview("detail.html", mvc.perform(get("/quality/" + id).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        writePreview("run.html", mvc.perform(get("/quality/runs/" + run2).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html")).andReturn().getResponse().getContentAsString());
    }

    @Test
    void handlesFreshnessEmptyTablesAndRestrictsCustomSql() {
        QualityTask window = task("window", List.of(rule("window-rows", RuleType.ROW_COUNT, "", "0", "3"))); window.setTimeColumn("occurred_at"); window.setWindowHours(1);
        long windowId = service.save(null, window, "dev"); long windowRun = service.start(windowId, 1, "dev"); waitFor(windowRun);
        assertThat(mapper.results(windowRun)).singleElement().satisfies(r -> { assertThat(r.getStatus()).isEqualTo("PASSED"); assertThat(r.getMetricValue()).isEqualByComparingTo("1"); });
        long empty = service.save(null, task("empty-fresh", List.of(rule("fresh", RuleType.FRESHNESS, "occurred_at", "1", ""))), "dev");
        long run = service.start(empty, 1, "dev"); waitFor(run);
        assertThat(mapper.results(run)).singleElement().satisfies(r -> { assertThat(r.getStatus()).isEqualTo("FAILED"); assertThat(r.getAnomalyCount()).isEqualTo(1); });
        QualityTask custom = task("custom", List.of(rule("custom", RuleType.CUSTOM_SQL, "", "SELECT id FROM quality_fixture WHERE amount < 0", "")));
        long id = service.save(null, custom, "dev"); long customRun = service.start(id, 1, "dev"); waitFor(customRun);
        assertThat(mapper.results(customRun)).singleElement().satisfies(r -> assertThat(r.getAnomalyCount()).isEqualTo(1));
        QualityTask sourced = task("source-custom", custom.getRules()); sourced.setSourceId(999L);
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(null, sourced, "dev"));
        QualityTask multi = task("multi-custom", List.of(rule("multi", RuleType.CUSTOM_SQL, "", "SELECT 1; SELECT 2", "")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.save(null, multi, "dev"));
    }

    private void waitFor(long runId) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!"RUNNING".equals(mapper.run(runId).getStatus())) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); fail("quality scan interrupted"); }
        }
        fail("quality scan did not finish within 10 seconds");
    }

    private static void writePreview(String name, String html) throws Exception {
        Path preview = Path.of("target", "quality-preview");
        Files.createDirectories(preview);
        Files.writeString(preview.resolve(name), html, StandardCharsets.UTF_8);
    }

    private static LocalDateTime now() { return LocalDateTime.now(TZ); }

    private static QualityTask task(String name, List<QualityRule> rules) {
        var t = new QualityTask();
        t.setName(name);
        t.setTableName(name.equals("empty-fresh") ? "quality_empty" : name.equals("window") ? "quality_window" : "quality_fixture");
        t.setOwner("dev");
        t.setDescription("");
        t.setCronExpression("");
        t.setTimeColumn("");
        t.setRules(new ArrayList<>(rules));
        return t;
    }

    private static QualityRule rule(String name, RuleType type, String columns, String one, String two) {
        var r = new QualityRule();
        r.setName(name);
        r.setType(type);
        r.setColumns(columns);
        r.setParameter1(one);
        r.setParameter2(two);
        r.setReferenceTable("");
        r.setReferenceColumn("");
        r.setTolerancePercent(BigDecimal.ZERO);
        r.setSeverity("CRITICAL");
        return r;
    }

    private static QualityRule reference() {
        var r = rule("reference", RuleType.REFERENCE, "ref_id", "", "");
        r.setReferenceTable("quality_reference");
        r.setReferenceColumn("id");
        return r;
    }
}
