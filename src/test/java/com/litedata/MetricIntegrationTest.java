package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.metric.*;
import com.litedata.model.*;
import com.litedata.modeling.ModelingService;
import com.litedata.service.QueryPage;
import com.litedata.store.MetricMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MetricIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("warehouse").withUsername("platform").withPassword("metric-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl); r.add("platform.doris.username", MYSQL::getUsername); r.add("platform.doris.password", MYSQL::getPassword);
        r.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("platform.internal-token", () -> "metric-test-internal-token-0123456789");
        r.add("platform.admin-password", () -> "MetricAdminPassword2026");
        r.add("platform.quality.scheduling-enabled", () -> "false");
        r.add("spring.flyway.clean-disabled", () -> "false");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricService metrics;
    @Autowired ModelingService modeling;
    @Autowired MetricMapper mapper;
    @Autowired MetricLineageService lineage;
    @Autowired Flyway flyway;
    long modelId;

    @BeforeEach void setup() {
        flyway.clean(); flyway.migrate();
        jdbc.execute("CREATE TABLE metric_orders(id BIGINT, amount DECIMAL(12,2), region VARCHAR(20), status VARCHAR(20), occurred_at DATETIME)");
        jdbc.update("INSERT INTO metric_orders VALUES(1,10,'east','paid',?),(2,20,'west','pending',?),(3,30,'east','paid',?)",
                LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(40));
        var model = new DataModel(); model.setTableName("metric_orders"); model.setName("指标订单"); model.setSubjectArea("交易");
        model.setFields(new ArrayList<>(List.of(field("id", "BIGINT", true), field("amount", "DECIMAL(12,2)", false),
                field("region", "VARCHAR(20)", false), field("status", "VARCHAR(20)", false), field("occurred_at", "DATETIME", false))));
        modelId = modeling.save(null, model, "dev");
    }
    @Test void savesThreeTypesAndExecutesGeneratedSqlAgainstMysqlFixtures() {
        long amount = metrics.save(null, atomic("amount", "SUM", "amount", "region"), "dev");
        long count = metrics.save(null, atomic("orders", "COUNT", "*", "region"), "dev");
        long paid = metrics.save(null, derived("paid_7d", amount, "status = 'paid' AND amount > 0"), "dev");
        long recent = metrics.save(null, derived("orders_7d", count, ""), "dev");
        long ratio = metrics.save(null, composite("average_7d", "[paid_7d] / [orders_7d]"), "dev");
        var values = jdbc.queryForList(metrics.require(ratio).getSqlText());
        assertThat(values).hasSize(2);
        assertThat(values.stream().filter(row -> row.get("region").equals("east")).findFirst().orElseThrow().get("metric_value").toString()).startsWith("10");
        assertThat(values.stream().filter(row -> row.get("region").equals("west")).findFirst().orElseThrow().get("metric_value")).isNull();
        assertThat(metrics.require(paid).getDimensions()).isEqualTo("region");
        assertThat(metrics.require(ratio).getDependencies()).extracting(MetricDependency::id).containsExactly(paid, recent);
        long scalar = metrics.save(null, atomic("scalar", "SUM", "amount", ""), "dev");
        long zero = metrics.save(null, composite("zero", "[scalar] / (2 - 2)"), "dev");
        assertThat(jdbc.queryForObject(metrics.require(zero).getSqlText(), Object.class)).isNull();
        assertThat(metrics.require(scalar).getSqlText()).contains("SUM(`amount`)");
    }
    @Test void preservesVersionSnapshotsAndRejectsStaleWritesCyclesAndReferencedArchives() {
        long atom = metrics.save(null, atomic("amount", "SUM", "amount", ""), "dev");
        long ratio = metrics.save(null, composite("ratio", "[amount] * 2"), "dev");
        String sql = metrics.require(ratio).getSqlText();
        var changed = metrics.require(atom); changed.setAggregation("AVG"); metrics.save(atom, changed, "dev");
        assertThat(metrics.require(atom).getVersion()).isEqualTo(2);
        assertThat(metrics.historyDefinition(atom, 1).getAggregation()).isEqualTo("SUM");
        assertThat(metrics.require(ratio).getSqlText()).isEqualTo(sql);
        assertThat(metrics.require(ratio).getDependencies().getFirst().version()).isEqualTo(1);
        assertThat(metrics.versionCount(atom)).isEqualTo(2);
        assertThat(metrics.versions(atom, new QueryPage(1, 20))).hasSize(2);
        changed.setVersion(1);
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(atom, changed, "dev")).withMessageContaining("变化");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.archive(atom, 2, "dev")).withMessageContaining("下游");
        var cyclic = metrics.require(ratio); cyclic.setFormula("[ratio] + [amount]");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(ratio, cyclic, "dev")).withMessageContaining("循环");
        long next = metrics.save(null, composite("next", "[ratio] * 2"), "dev");
        cyclic.setFormula("[next] + [amount]");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(ratio, cyclic, "dev")).withMessageContaining("循环");
        assertThatIllegalArgumentException().isThrownBy(() -> modeling.archive(modelId, 1, "dev")).withMessageContaining("指标");
        metrics.archive(next, 1, "dev"); metrics.archive(ratio, 1, "dev"); metrics.archive(atom, 2, "dev");
        assertThat(metrics.count("", "")).isZero();
        assertThat(metrics.historyDefinition(atom, 3).isArchived()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='METRIC_SAVE'", Integer.class)).isEqualTo(4);
    }
    @Test void validatesSourceFieldsTypesDimensionsAndDerivedConditions() {
        var invalid = atomic("bad", "SUM", "region", "");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, invalid, "dev")).withMessageContaining("数值");
        invalid.setAggregation("COUNT"); invalid.setMeasureColumn("missing");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, invalid, "dev")).withMessageContaining("度量字段");
        invalid.setMeasureColumn("*"); invalid.setDimensions("missing");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, invalid, "dev")).withMessageContaining("维度");
        long atom = metrics.save(null, atomic("amount", "SUM", "amount", "region"), "dev");
        metrics.save(null, atomic("orders", "COUNT", "*", ""), "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, composite("bad", "[amount] / [orders]"), "dev")).withMessageContaining("统计维度");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, derived("bad", atom, "amount > 0; DROP TABLE metric_orders"), "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, derived("bad", atom, "missing = 1"), "dev"));
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, composite("bad", "[unknown] + 1"), "dev"));
        var noTime = metrics.require(atom); noTime.setTimeColumn(""); metrics.save(atom, noTime, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.save(null, derived("bad", atom, ""), "dev")).withMessageContaining("时间字段");
    }
    @Test void rendersSeparatePagesPreservesFiltersChecksRolesAndKeepsInvalidForms() throws Exception {
        long id = metrics.save(null, atomic("amount", "SUM", "amount", "region"), "dev");
        metrics.save(null, atomic("orders", "COUNT", "*", "region"), "dev");
        long derived = metrics.save(null, derived("recent", id, "status IN ('paid', 'pending')"), "dev");
        long composite = metrics.save(null, composite("average", "[amount] / [orders]"), "dev");
        for (String path : List.of("/metrics", "/metrics/new", "/metrics/new?type=COMPOSITE", "/metrics/new?type=DERIVED", "/metrics/" + id + "/edit", "/metrics/" + derived, "/metrics/" + composite, "/metrics/" + id + "/versions/1"))
            mvc.perform(get(path).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/metrics").param("metricType", "ATOMIC").param("keyword", "a").param("pageSize", "1").param("pageNum", "999").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 1L)).andExpect(model().attribute("pageNum", 1));
        mvc.perform(get("/metrics").param("metricType", "ATOMIC").param("pageSize", "1").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 2L)).andExpect(content().string(org.hamcrest.Matchers.containsString("metricType=ATOMIC")));
        for (String parameter : List.of("0", "abc", "2147483648"))
            mvc.perform(get("/metrics").param("pageNum", parameter).with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest());
        mvc.perform(get("/metrics").param("metricType", "UNKNOWN").with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest());
        mvc.perform(get("/metrics").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("新增原子指标"))));
        for (String path : List.of("/metrics/new", "/metrics/" + id + "/edit"))
            mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/metrics").with(user("viewer").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/metrics").with(user("dev").roles("DEVELOPER"))).andExpect(status().isForbidden());
        mvc.perform(post("/metrics").with(user("dev").roles("DEVELOPER")).with(csrf()).param("code", "kept_code").param("name", ""))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error")).andExpect(content().string(org.hamcrest.Matchers.containsString("kept_code")));
        mvc.perform(post("/metrics").with(user("dev").roles("DEVELOPER")).with(csrf()).param("code", "kept_code").param("name", "keep").param("type", "INVALID"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        for (String path : List.of("/metrics/999", "/metrics/" + id + "/versions/999"))
            mvc.perform(get(path).with(user("dev").roles("DEVELOPER"))).andExpect(status().isNotFound());
        Path preview = Path.of("target", "metric-preview"); Files.createDirectories(preview);
        Map<String,String> previews = Map.of("list", "/metrics", "atomic", "/metrics/new", "composite", "/metrics/new?type=COMPOSITE", "derived", "/metrics/new?type=DERIVED", "detail", "/metrics/" + composite);
        for (var entry : previews.entrySet()) Files.writeString(preview.resolve(entry.getKey() + ".html"), mvc.perform(get(entry.getValue()).with(user("dev").roles("DEVELOPER"))).andReturn().getResponse().getContentAsString(), StandardCharsets.UTF_8);
    }
    @Test void capturesFieldOriginsThroughSqlAndSyncAndFreezesEveryVersion() throws Exception {
        configureLineage();
        long amount = metrics.save(null, atomic("paid_amount", "SUM", "amount", "region"), "dev");
        long recent = metrics.save(null, derived("paid_amount_7d", amount, "status = 'paid'"), "dev");
        long composite = metrics.save(null, composite("amount_ratio", "[paid_amount_7d] / [paid_amount]"), "dev");
        var first = metrics.require(amount);
        var graph = lineage.view(first, "metric_value");
        assertThat(graph.snapshot()).isTrue();
        assertThat(graph.nodes()).extracting(MetricLineage.Node::label).contains("demo.orders.net_amount", "warehouse.metric_orders.amount");
        assertThat(graph.edges()).anySatisfy(e -> { assertThat(e.role()).isEqualTo("表达式"); assertThat(e.expression()).contains("net_amount / 100"); });
        assertThat(graph.edges()).anySatisfy(e -> { assertThat(e.role()).isEqualTo("SQL 筛选"); assertThat(e.expression()).contains("deleted"); });
        assertThat(graph.edges()).anySatisfy(e -> assertThat(e.role()).isEqualTo("同步同名映射"));
        var dimensions = lineage.view(first, "region");
        assertThat(dimensions.nodes()).extracting(MetricLineage.Node::label).contains("demo.orders.region").doesNotContain("demo.orders.net_amount");
        var derived = lineage.view(metrics.require(recent), "metric_value");
        assertThat(derived.edges()).extracting(MetricLineage.Edge::role).contains("时间窗口", "业务筛选", "原子指标");
        assertThat(derived.nodes()).extracting(MetricLineage.Node::label).contains("demo.orders.occurred_at", "demo.orders.status");
        var formula = lineage.view(metrics.require(composite), "metric_value");
        assertThat(formula.edges()).extracting(MetricLineage.Edge::role).contains("复合公式", "维度关联");
        assertThat(formula.nodes()).anySatisfy(n -> assertThat(n.href()).contains("/versions/1/lineage"));
        jdbc.update("UPDATE sql_task SET sql_text=REPLACE(sql_text,'o.net_amount / 100','o.id'),version=version+1 WHERE model_id=?", modelId);
        assertThat(lineage.view(metrics.historyDefinition(amount, 1), "metric_value")).isEqualTo(graph);
        first.setDescription("重新核对来源后保存"); metrics.save(amount, first, "dev");
        var second = lineage.view(metrics.require(amount), "metric_value");
        assertThat(second.nodes()).extracting(MetricLineage.Node::label).contains("demo.orders.id").doesNotContain("demo.orders.net_amount");
        assertThat(lineage.view(metrics.require(composite), "metric_value")).isEqualTo(formula);
        var changedMeasure = metrics.require(amount); changedMeasure.setMeasureColumn("id"); metrics.save(amount, changedMeasure, "dev");
        assertThat(lineage.view(metrics.require(amount), "metric_value").nodes()).extracting(MetricLineage.Node::label)
                .contains("warehouse.metric_orders.id").doesNotContain("warehouse.metric_orders.amount");
        for (String path : List.of("/metrics/" + composite + "/lineage", "/metrics/" + amount + "/versions/1/lineage", "/metrics/" + amount + "/lineage?field=region", "/metrics/" + amount + "/lineage?field=all"))
            mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("输出字段")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"\""))));
        mvc.perform(get("/metrics/" + amount + "/lineage").param("field", "missing").with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest());
        mvc.perform(get("/metrics/" + amount + "/lineage")).andExpect(status().is3xxRedirection());
        Path preview = Path.of("target", "metric-preview"); Files.createDirectories(preview);
        Files.writeString(preview.resolve("lineage.html"), mvc.perform(get("/metrics/" + composite + "/lineage").with(user("viewer").roles("VIEWER"))).andReturn().getResponse().getContentAsString(), StandardCharsets.UTF_8);
        String stored = mapper.version(composite, 1).definitionJson();
        assertThat(stored).doesNotContain("jdbc:mysql", "test-encrypted", "source_reader");
    }
    @Test void marksLegacyVersionsAndAmbiguousOrUnsupportedMappingsWithoutInventingFields() throws Exception {
        configureLineage();
        long atom = metrics.save(null, atomic("legacy_amount", "SUM", "amount", "region"), "dev");
        jdbc.update("UPDATE metric_version SET definition_json=JSON_REMOVE(definition_json,'$.lineageSnapshot') WHERE metric_id=?", atom);
        var old = metrics.historyDefinition(atom, 1);
        assertThat(old.getLineageSnapshot()).isNull();
        var legacy = lineage.view(old, "metric_value");
        assertThat(legacy.snapshot()).isFalse(); assertThat(legacy.warnings()).anySatisfy(w -> assertThat(w).contains("当前配置"));
        mvc.perform(get("/metrics/" + atom + "/versions/1/lineage").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("未找到该版本的冻结血缘快照")));
        jdbc.update("INSERT INTO pipeline(name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) SELECT 'ambiguous',source_id,source_table,watermark_column,'metric_orders','','' FROM pipeline LIMIT 1");
        long ambiguous = metrics.save(null, atomic("ambiguous_amount", "SUM", "amount", ""), "dev");
        var graph = lineage.view(metrics.require(ambiguous), "metric_value");
        assertThat(graph.warnings()).anySatisfy(w -> assertThat(w).contains("多个写入配置"));
        assertThat(graph.nodes()).noneSatisfy(n -> assertThat(n.kind()).isEqualTo("SOURCE_FIELD"));
        jdbc.update("DELETE FROM pipeline WHERE name='ambiguous'");
        jdbc.update("UPDATE sql_task SET sql_text='INSERT INTO metric_orders SELECT * FROM (SELECT * FROM ods_metric_orders) nested' WHERE model_id=?", modelId);
        long unsupported = metrics.save(null, atomic("unsupported_amount", "SUM", "amount", ""), "dev");
        assertThat(lineage.view(metrics.require(unsupported), "metric_value").warnings()).anySatisfy(w -> assertThat(w).contains("映射无法确定"));
        metrics.archive(unsupported, 1, "dev");
        var archived = metrics.historyDefinition(unsupported, 2);
        assertThat(lineage.view(archived, "metric_value").outputs().getFirst().key()).contains(":v2:");
        mvc.perform(get("/metrics/" + unsupported + "/versions/2/lineage").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
    }
    @Test void defaultsToDirectFieldOriginsAndKeepsConditionsAvailableWithoutRewritingSnapshots() throws Exception {
        configureLineage();
        var controls = new DataModel(); controls.setTableName("metric_controls"); controls.setName("质量与产品条件"); controls.setSubjectArea("交易");
        controls.setFields(new ArrayList<>(List.of(field("id", "BIGINT", true), field("water_m3", "DECIMAL(12,2)", false), field("product_code", "VARCHAR(20)", false))));
        modeling.save(null, controls, "dev");
        jdbc.update("UPDATE sql_task SET sql_text=? WHERE model_id=?",
                "INSERT INTO metric_orders(id,amount,region,status,occurred_at) SELECT o.id,o.net_amount/100,o.region,o.status,o.occurred_at FROM ods_metric_orders o JOIN metric_controls c ON o.id=c.id WHERE o.status <> 'deleted' AND c.water_m3 >= 0 AND c.product_code='A'", modelId);
        long atom = metrics.save(null, atomic("compact_amount", "SUM", "amount", "region"), "dev");
        String frozen = mapper.version(atom, 1).definitionJson();
        var directResult = mvc.perform(get("/metrics/" + atom + "/lineage").param("field", "region").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(model().attribute("mode", "direct"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("影响结果的条件"))).andReturn();
        var direct = (MetricLineage.Graph) directResult.getModelAndView().getModel().get("graph");
        assertThat(direct.nodes()).extracting(MetricLineage.Node::label).contains("demo.orders.region", "warehouse.metric_orders.region")
                .noneMatch(label -> label.endsWith(".water_m3") || label.endsWith(".product_code") || label.endsWith(".status") || label.endsWith(".*"));
        var presentation = (MetricLineageProjection.Projection) directResult.getModelAndView().getModel().get("projection");
        assertThat(presentation.hiddenNodeCount()).isPositive();
        assertThat(presentation.contexts()).anySatisfy(context -> {
            assertThat(context.role()).isEqualTo("SQL 筛选");
            assertThat(context.inputs()).contains("warehouse.metric_controls.water_m3");
        });
        var fullResult = mvc.perform(get("/metrics/" + atom + "/versions/1/lineage").param("field", "region").param("mode", "full").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(model().attribute("mode", "full"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&amp;mode=full"))).andReturn();
        var full = (MetricLineage.Graph) fullResult.getModelAndView().getModel().get("graph");
        assertThat(full.nodes()).extracting(MetricLineage.Node::label).contains("warehouse.metric_controls.water_m3", "warehouse.metric_controls.product_code");
        assertThat(direct.nodes().size()).isLessThan(full.nodes().size());
        var valueResult = mvc.perform(get("/metrics/" + atom + "/lineage").param("mode", "direct").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk()).andReturn();
        var values = (MetricLineage.Graph) valueResult.getModelAndView().getModel().get("graph");
        assertThat(values.nodes()).extracting(MetricLineage.Node::label).contains("demo.orders.net_amount").doesNotContain("demo.orders.region");
        long count = metrics.save(null, atomic("compact_rows", "COUNT", "*", "region"), "dev");
        var countResult = mvc.perform(get("/metrics/" + count + "/lineage").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk()).andReturn();
        var rowGraph = (MetricLineage.Graph) countResult.getModelAndView().getModel().get("graph");
        assertThat(rowGraph.nodes()).extracting(MetricLineage.Node::label).contains("warehouse.metric_orders.*", "demo.orders.*");
        assertThat(rowGraph.edges()).extracting(MetricLineage.Edge::role).contains("行数聚合", "SQL 行集", "同步行集");
        assertThat(mapper.version(atom, 1).definitionJson()).isEqualTo(frozen);
        for (String path : List.of("/metrics/" + atom + "/lineage", "/metrics/" + atom + "/versions/1/lineage"))
            mvc.perform(get(path).param("mode", "unexpected").with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest());
    }

    private void configureLineage() {
        var source = new DataModel(); source.setTableName("ods_metric_orders"); source.setName("源订单"); source.setSubjectArea("交易");
        source.setFields(new ArrayList<>(List.of(field("id", "BIGINT", true), field("net_amount", "DECIMAL(12,2)", false),
                field("region", "VARCHAR(20)", false), field("status", "VARCHAR(20)", false), field("occurred_at", "DATETIME", false))));
        modeling.save(null, source, "dev");
        jdbc.update("INSERT INTO data_source(name,jdbc_url,username,encrypted_password) VALUES('演示业务源','jdbc:mysql://example.invalid:3306/demo','source_reader','test-encrypted')");
        long sourceId = jdbc.queryForObject("SELECT id FROM data_source WHERE name='演示业务源'", Long.class);
        jdbc.update("INSERT INTO pipeline(name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) VALUES('同步订单',?,'orders','occurred_at','ods_metric_orders','','')", sourceId);
        jdbc.update("INSERT INTO sql_task(name,model_id,sql_text) VALUES('订单金额换算',?,?)", modelId,
                "INSERT INTO metric_orders(id,amount,region,status,occurred_at) SELECT o.id,o.net_amount / 100,o.region,o.status,o.occurred_at FROM ods_metric_orders o WHERE o.status <> 'deleted'");
    }
    private MetricDefinition atomic(String code, String aggregation, String column, String dimensions) {
        var m = definition(code, MetricType.ATOMIC); m.setModelId(modelId); m.setAggregation(aggregation); m.setMeasureColumn(column); m.setDimensions(dimensions); m.setTimeColumn("occurred_at"); return m;
    }
    private static MetricDefinition derived(String code, long base, String filter) {
        var m = definition(code, MetricType.DERIVED); m.setBaseMetricId(base); m.setFilterExpression(filter); return m;
    }
    private static MetricDefinition composite(String code, String formula) {
        var m = definition(code, MetricType.COMPOSITE); m.setFormula(formula); return m;
    }
    private static MetricDefinition definition(String code, MetricType type) {
        var m = new MetricDefinition(); m.setCode(code); m.setName(code); m.setType(type); m.setSubjectArea("交易"); m.setOwner("dev"); m.setDescription("测试口径 " + code); return m;
    }
    private static ModelField field(String name, String type, boolean key) {
        var f = new ModelField(); f.setName(name); f.setDataType(type); f.setKeyColumn(key); return f;
    }
}
