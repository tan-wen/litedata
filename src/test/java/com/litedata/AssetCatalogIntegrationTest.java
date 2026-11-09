package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.catalog.*;
import com.litedata.metric.*;
import com.litedata.store.AssetCatalogMapper;
import com.litedata.web.AssetCatalogController.Category;

import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Real MySQL UNION pagination, profile concurrency, safe MVC projections and existing resource links.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AssetCatalogIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("platform").withUsername("platform").withPassword("catalog-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token", () -> "catalog-test-internal-token-0123456789");
        registry.add("platform.admin-password", () -> "CatalogAdminPassword2026");
        registry.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("platform.doris.username", MYSQL::getUsername);
        registry.add("platform.doris.password", MYSQL::getPassword);
        registry.add("platform.quality.scheduling-enabled", () -> false);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AssetCatalogService catalog;
    @Autowired
    AssetCatalogMapper mapper;
    @Autowired
    MetricService metrics;

    @BeforeEach
    void clean() {
        TestDictionaries.seed(jdbc);
        for (String table : List.of("asset_profile", "quality_result", "quality_run", "quality_rule", "quality_task",
            "metric_dependency", "metric_version", "metric_definition", "metadata_column", "metadata_table", "metadata_scope",
            "model_execution", "model_field", "data_model", "api_credential", "service_call", "data_service", "data_source", "audit_event"))
            jdbc.update("DELETE FROM " + table);
    }

    @Test
    void unifiesFourResourceKindsAndPaginatesInStableOrder() throws Exception {
        seed();
        assertThat(catalog.stats()).isEqualTo(new AssetCatalogService.Stats(4, 1, 1, 1, 1));
        var all = AssetFilters.from(Map.of());
        var first = catalog.list(all, new com.litedata.service.QueryPage(1, 2));
        var second = catalog.list(all, new com.litedata.service.QueryPage(2, 2));
        assertThat(first).extracting(a -> a.getType().name()).containsExactly("METRIC", "MODEL");
        assertThat(second).extracting(a -> a.getType().name()).containsExactly("SERVICE", "TABLE");
        mvc.perform(get("/assets").param("pageSize", "2").with(user("reader").roles("VIEWER")))
            .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 4L)).andExpect(model().attribute("hasMore", true));
        mvc.perform(get("/assets").param("pageNum", "999").param("pageSize", "2").with(user("reader").roles("VIEWER")))
            .andExpect(status().isOk()).andExpect(model().attribute("pageNum", 2));
        for (var entry : catalog.list(all, new com.litedata.service.QueryPage(1, 20))) {
            mvc.perform(get(entry.getPath()).with(user("reader").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-query-marker"))));
            mvc.perform(get(entry.getResourcePath()).with(user("reader").roles("VIEWER"))).andExpect(status().isOk());
        }
    }

    @Test
    void browsesCategoriesWithGlobalCountsAndKeepsSearchAndPaginationContext() throws Exception {
        seed();
        jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer) VALUES('ods_extra','额外模型','交易','ODS')");
        var result = mvc.perform(get("/assets").with(user("reader").roles("VIEWER")))
            .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 5L))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("asset-directory-layout")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-label=\"资产分类\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"asset-type\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("数据表"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("编辑目录信息</a>"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">查看</a>"))))
            .andReturn();
        @SuppressWarnings("unchecked")
        var categories = (List<Category>) result.getModelAndView().getModel().get("categories");
        assertThat(categories).extracting(Category::label).containsExactly("物理表", "逻辑模型", "指标", "数据服务");
        assertThat(categories).extracting(Category::count).containsExactly(1L, 2L, 1L, 1L);
        assertThat(categories.get(1).children()).extracting(child -> child.key()).containsExactly("ODS", "DIM", "DWD", "DWS", "ADS");
        assertThat(categories.get(1).children()).extracting(child -> child.count()).containsExactly(2L, 0L, 0L, 0L, 0L);
        assertThat(categories.get(2).children()).extracting(child -> child.label()).containsExactly("原子指标", "复合指标", "派生指标");
        for (var category : categories) {
            var filtered = mvc.perform(get(category.path()).with(user("reader").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(model().attribute("assetType", category.type()))
                .andExpect(model().attribute("totalCount", category.count())).andReturn();
            @SuppressWarnings("unchecked")
            var assets = (List<AssetEntry>) filtered.getModelAndView().getModel().get("assets");
            assertThat(assets).allSatisfy(asset -> assertThat(asset.getType().name()).isEqualTo(category.type()));
            assertThat(filtered.getResponse().getContentAsString()).contains("aria-current=\"true\"");
        }
        var filtered = mvc.perform(get("/assets").param("assetType", "MODEL").param("assetCategory", "ODS").param("keyword", "订单 + 核心&资产")
                .param("subjectArea", "交易").param("owner", "负责人").param("tag", "核心").param("assetStatus", "ACTIVE")
                .param("pageNum", "99").param("pageSize", "1").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 0L)).andReturn();
        @SuppressWarnings("unchecked")
        var filteredCategories = (List<Category>) filtered.getModelAndView().getModel().get("categories");
        assertThat(filteredCategories).extracting(Category::count).containsExactly(1L, 2L, 1L, 1L);
        var tablePath = filteredCategories.getFirst().path();
        assertThat(tablePath).contains("assetType=TABLE", "pageNum=1", "pageSize=1", "assetStatus=ACTIVE", "%2B", "%26");
        assertThat(tablePath).doesNotContain("assetCategory=");
        mvc.perform(get(java.net.URI.create(tablePath)).with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(model().attribute("keyword", "订单 + 核心&资产"))
            .andExpect(model().attribute("subjectArea", "交易")).andExpect(model().attribute("owner", "负责人"))
            .andExpect(model().attribute("tag", "核心"));
        String root = (String) filtered.getModelAndView().getModel().get("treeRootPath");
        mvc.perform(get(java.net.URI.create(root)).with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(model().attribute("assetType", "")).andExpect(model().attribute("assetCategory", ""));
        String reset = (String) filtered.getModelAndView().getModel().get("resetPath");
        mvc.perform(get(reset).with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(model().attribute("assetType", "MODEL"))
            .andExpect(model().attribute("assetCategory", "ODS"))
            .andExpect(model().attribute("keyword", "")).andExpect(model().attribute("totalCount", 2L));
    }

    @Test
    void subcategoriesPartitionSourcesLayersMetricKindsAndServiceTopics() throws Exception {
        seed();
        for (String layer : List.of("DIM", "DWD", "DWS", "ADS"))
            jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer,archived) VALUES(?,?, '交易',?,?)",
                layer.toLowerCase(Locale.ROOT) + "_extra", layer + "模型", layer, layer.equals("ADS"));
        for (String kind : List.of("COMPOSITE", "DERIVED"))
            jdbc.update("INSERT INTO metric_definition(code,name,type,subject_area,owner,sql_text) VALUES(?,?,?,'交易','dev','SELECT 1')",
                kind.toLowerCase(Locale.ROOT) + "_metric", kind + "指标", kind);
        for (String sourceName : List.of("ERP", "CRM")) {
            jdbc.update("INSERT INTO data_source(name,jdbc_url,username,encrypted_password) VALUES(?,'jdbc:mysql://localhost/source','reader','unused')", sourceName);
            String scope = "source:" + id("data_source");
            jdbc.update("INSERT INTO metadata_scope(scope_key) VALUES(?)", scope);
            for (String database : sourceName.equals("ERP") ? List.of("business", "archive") : List.of("business"))
                jdbc.update("INSERT INTO metadata_table(scope_key,catalog_name,table_name,comment,schema_hash) VALUES(?,?,'ods_orders','同名表',REPEAT('b',64))", scope, database);
        }
        String topic = "交易 + 核心&资产";
        for (String area : List.of(topic, topic, "unclassified")) {
            jdbc.update("INSERT INTO data_service(name,slug,query_sql) VALUES('目录测试服务',?,'SELECT 1')", "service_" + UUID.randomUUID());
            var profile = new AssetProfile();
            profile.setSubjectArea(area);
            catalog.save("SERVICE", id("data_service"), profile, "dev");
        }
        var listing = mvc.perform(get("/assets").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked")
        var categories = (List<Category>) listing.getModelAndView().getModel().get("categories");
        assertThat(categories).extracting(Category::count).containsExactly(4L, 5L, 3L, 4L);
        assertThat(categories.getFirst().children()).extracting(child -> child.label()).containsExactly("CRM", "Doris 数仓", "ERP");
        assertThat(categories.getFirst().children()).extracting(child -> child.count()).containsExactly(1L, 1L, 2L);
        assertThat(categories.getLast().children()).extracting(child -> child.key())
            .containsExactlyInAnyOrder("area:" + topic, "area:unclassified", "unclassified");
        for (var category : categories) {
            assertThat(category.children().stream().mapToLong(child -> child.count()).sum()).isEqualTo(category.count());
            for (var child : category.children()) {
                var childPage = UriComponentsBuilder.fromUriString(child.path()).replaceQueryParam("pageSize", "1")
                    .replaceQueryParam("pageNum", "999").build(true).toUri();
                var response = mvc.perform(get(childPage)
                        .with(user("reader").roles("VIEWER")))
                    .andExpect(status().isOk()).andExpect(model().attribute("totalCount", child.count()))
                    .andExpect(model().attribute("assetCategory", child.key())).andReturn();
                @SuppressWarnings("unchecked")
                var assets = (List<AssetEntry>) response.getModelAndView().getModel().get("assets");
                assertThat(assets).hasSize(1);
                var asset = assets.getFirst();
                assertThat(asset.getType().name()).isEqualTo(category.type());
                switch (asset.getType()) {
                    case TABLE -> assertThat(asset.getScopeKey()).isEqualTo(child.key());
                    case MODEL -> assertThat(asset.getLayer()).isEqualTo(child.key());
                    case METRIC -> assertThat(jdbc.queryForObject("SELECT type FROM metric_definition WHERE id=?", String.class, asset.getResourceId())).isEqualTo(child.key());
                    case SERVICE -> assertThat(asset.getSubjectArea()).isEqualTo(child.key().equals("unclassified") ? "" : child.key().substring(5));
                }
            }
        }
        long service = id("data_service");
        var cleared = new AssetProfile();
        cleared.setVersion(1);
        catalog.save("SERVICE", service, cleared, "dev");
        assertThat(catalog.categoryCounts().stream().filter(group -> group.type() == AssetType.SERVICE).toList())
            .extracting(AssetCatalogService.CategoryCount::key).containsExactlyInAnyOrder("area:" + topic, "unclassified");
        jdbc.update("UPDATE data_source SET archived=TRUE WHERE name='CRM'");
        jdbc.update("DELETE FROM data_source WHERE name='ERP'");
        assertThat(catalog.categoryCounts().stream().filter(group -> group.type() == AssetType.TABLE).toList())
            .extracting(AssetCatalogService.CategoryCount::label).containsExactly("Doris 数仓", "来源已移除");
    }

    @Test
    void editsFromListAndDetailReturnToTheirOriginAndPreserveContextOnErrors() throws Exception {
        seed();
        long id = id("data_model");
        String assetPath = "/assets/MODEL/" + id;
        jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer) VALUES('ods_more_orders','更多订单','交易','ODS')");
        var listing = mvc.perform(get("/assets").param("assetType", "MODEL").param("assetCategory", "ODS").param("keyword", "订单")
                .param("pageNum", "2").param("pageSize", "1").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"" + assetPath + "\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("编辑目录信息</a>")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("assetCategory=ODS")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"assetCategory\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">查看</a>"))))
            .andReturn();
        @SuppressWarnings("unchecked")
        var editPaths = (Map<String, String>) listing.getModelAndView().getModel().get("editPaths");
        var form = mvc.perform(get(java.net.URI.create(editPaths.get(assetPath))).with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(model().attribute("returnTo", "list"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"return_assetType\""))).andReturn();
        String listPath = (String) form.getModelAndView().getModel().get("backPath");
        assertThat(listPath).contains("assetType=MODEL", "assetCategory=ODS", "pageNum=2", "pageSize=1");
        @SuppressWarnings("unchecked")
        var returnParams = (Map<String, String>) form.getModelAndView().getModel().get("returnParams");
        var invalid = post(assetPath).with(user("dev").roles("DEVELOPER")).with(csrf())
            .param("version", "0").param("owner", "新负责人").param("sensitivity", "INVALID");
        returnParams.forEach(invalid::param);
        mvc.perform(invalid).andExpect(status().isOk()).andExpect(view().name("asset-form"))
            .andExpect(model().attribute("backPath", listPath)).andExpect(model().attribute("returnParams", returnParams))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("新负责人")));
        var save = post(assetPath).with(user("dev").roles("DEVELOPER")).with(csrf())
            .param("version", "0").param("owner", "新负责人").param("sensitivity", "INTERNAL");
        returnParams.forEach(save::param);
        mvc.perform(save).andExpect(redirectedUrl(listPath));
        mvc.perform(get(assetPath).with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/edit?returnTo=detail")));
        mvc.perform(get(assetPath + "/edit").param("returnTo", "detail").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(model().attribute("backPath", assetPath));
        mvc.perform(post(assetPath).param("returnTo", "detail").param("version", "1").param("sensitivity", "INTERNAL")
                .with(user("dev").roles("DEVELOPER")).with(csrf()))
            .andExpect(redirectedUrl(assetPath));
    }

    @Test
    void savesAnnotationsWithoutChangingSourceAndRetainsAllFilters() throws Exception {
        seed();
        long id = id("data_model");
        mvc.perform(post("/assets/MODEL/" + id).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("version", "0").param("businessName", "统一订单资产").param("subjectArea", "交易主题")
                .param("owner", "张三").param("tags", "核心, 订单,核心").param("description", "订单业务说明")
                .param("updateFrequency", "每小时").param("sensitivity", "CONFIDENTIAL"))
            .andExpect(redirectedUrl("/assets")).andExpect(flash().attribute("success", "资产描述已保存"));
        var entry = catalog.require("MODEL", id);
        assertThat(entry.getName()).isEqualTo("统一订单资产");
        assertThat(entry.getTags()).isEqualTo("核心,订单");
        assertThat(entry.getSensitivityLabel()).isEqualTo("敏感");
        assertThat(jdbc.queryForObject("SELECT name FROM data_model WHERE id=?", String.class, id)).isEqualTo("订单模型");
        assertThat(catalog.require("TABLE", id("metadata_table")).getOwner()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='ASSET_PROFILE_SAVE'", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer) VALUES('ods_more_orders','更多订单','交易主题','ODS')");
        var other = new AssetProfile();
        other.setOwner("张三");
        other.setTags("核心");
        catalog.save("MODEL", id("data_model"), other, "dev");
        var params = Map.of("keyword", "订单", "assetType", "MODEL", "subjectArea", "交易主题", "owner", "张三", "tag", "核心", "assetStatus", "ACTIVE");
        mvc.perform(get("/assets").params(new org.springframework.util.LinkedMultiValueMap<>(params.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())))))
                .param("pageSize", "1").with(user("dev").roles("DEVELOPER")))
            .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 2L)).andExpect(model().attribute("hasMore", true))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("assetType=MODEL")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("assetStatus=ACTIVE")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("tag=")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"assetType\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"assetStatus\"")));
        assertThat(catalog.count(AssetFilters.from(Map.of("tag", "核")))).isZero();
        var blank = new AssetProfile();
        blank.setVersion(1);
        catalog.save("MODEL", id, blank, "dev");
        jdbc.update("UPDATE data_model SET name='新订单模型' WHERE id=?", id);
        assertThat(catalog.require("MODEL", id).getName()).isEqualTo("新订单模型");
    }

    @Test
    void lifecycleChangesAndRemovedResourcesAreReflectedWithoutCatalogSynchronization() throws Exception {
        seed();
        long model = id("data_model"), metric = id("metric_definition"), table = id("metadata_table"), service = id("data_service");
        metrics.archive(metric, 1, "dev");
        jdbc.update("UPDATE data_model SET archived=TRUE WHERE id=?", model);
        jdbc.update("UPDATE metadata_table SET present=FALSE WHERE id=?", table);
        jdbc.update("UPDATE data_service SET published=TRUE WHERE id=?", service);
        assertThat(catalog.require("MODEL", model).getStatus()).isEqualTo("ARCHIVED");
        assertThat(catalog.require("MODEL", model).getResourcePath()).isNull();
        mvc.perform(get("/assets/MODEL/" + model).with(user("reader").roles("VIEWER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("打开原资源</a>"))));
        assertThat(catalog.require("TABLE", table).getStatus()).isEqualTo("MISSING");
        assertThat(catalog.require("SERVICE", service).getStatus()).isEqualTo("PUBLISHED");
        var archived = catalog.require("METRIC", metric);
        mvc.perform(get(archived.getResourcePath()).with(user("reader").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get(archived.getLineagePath()).with(user("reader").roles("VIEWER"))).andExpect(status().isOk());
        assertThat(catalog.count(AssetFilters.from(Map.of("assetStatus", "ARCHIVED")))).isEqualTo(2);
        jdbc.update("DELETE FROM data_service WHERE id=?", service);
        mvc.perform(get("/assets/SERVICE/" + service).with(user("dev").roles("DEVELOPER"))).andExpect(status().isNotFound());
        assertThat(catalog.stats().services()).isZero();
    }

    @Test
    void rejectsStaleEditsAndPreservesInvalidFormValues() throws Exception {
        seed();
        long id = id("data_model");
        var profile = new AssetProfile();
        profile.setOwner("甲");
        catalog.save("MODEL", id, profile, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.save("MODEL", id, profile, "dev")).withMessageContaining("已被其他人更新");
        profile.setVersion(1);
        profile.setOwner("乙");
        catalog.save("MODEL", id, profile, "dev");
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.save("MODEL", id, profile, "dev")).withMessageContaining("已被其他人更新");
        mvc.perform(post("/assets/MODEL/" + id).with(user("dev").roles("DEVELOPER")).with(csrf())
                .param("version", "2").param("owner", "保留负责人").param("sensitivity", "INVALID"))
            .andExpect(status().isOk()).andExpect(view().name("asset-form"))
            .andExpect(model().attributeExists("error")).andExpect(content().string(org.hamcrest.Matchers.containsString("保留负责人")));
        assertThat(mapper.profile(AssetType.MODEL, id).getOwner()).isEqualTo("乙");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='ASSET_PROFILE_SAVE'", Integer.class)).isEqualTo(2);
    }

    @Test
    void limitsEditingToWritersAndRequiresCsrfAndValidFilters() throws Exception {
        seed();
        long id = id("data_model");
        String path = "/assets/MODEL/" + id;
        mvc.perform(get("/assets")).andExpect(status().is3xxRedirection());
        mvc.perform(get(path + "/edit").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("reader").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("dev").roles("DEVELOPER"))).andExpect(status().isForbidden());
        mvc.perform(get(path + "/edit").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk());
        mvc.perform(get(path).with(user("reader").roles("VIEWER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("编辑目录信息</a>"))));
        for (String query : List.of("assetType=INVALID", "assetStatus=INVALID", "pageNum=0", "pageSize=1001", "keyword=" + "x".repeat(101),
            "assetCategory=ODS", "assetType=MODEL&assetCategory=ATOMIC", "assetType=METRIC&assetCategory=ODS",
            "assetType=TABLE&assetCategory=source:0", "assetType=SERVICE&assetCategory=ODS", "assetType=SERVICE&assetCategory=area:"))
            mvc.perform(get("/assets?" + query).with(user("dev").roles("DEVELOPER"))).andExpect(status().isBadRequest());
        for (String suffix : List.of("BAD/1", "MODEL/0", "MODEL/99999999"))
            mvc.perform(get("/assets/" + suffix).with(user("dev").roles("DEVELOPER"))).andExpect(status().isNotFound());
    }

    @Test
    void linksQualityBySourceIdentityAndEscapesBusinessText() throws Exception {
        seed();
        long table = id("metadata_table");
        jdbc.update("INSERT INTO data_source(name,jdbc_url,username,encrypted_password) VALUES('业务库','jdbc:mysql://localhost/source','reader','catalog-secret-marker')");
        long source = id("data_source");
        jdbc.update("INSERT INTO quality_task(name,source_id,table_name,owner) VALUES('同名源表质量',?,'ods_orders','dev')", source);
        for (int i = 0; i < 12; i++)
            jdbc.update("INSERT INTO quality_task(name,table_name,owner) VALUES(?,'ods_orders','dev')", "数仓质量" + i);
        var asset = catalog.require("TABLE", table);
        assertThat(catalog.qualityCount(asset)).isEqualTo(12);
        assertThat(catalog.qualityLinks(asset)).hasSize(10);
        assertThat(catalog.qualityLinks(asset)).allSatisfy(q -> assertThat(q.name()).startsWith("数仓质量"));
        var profile = new AssetProfile();
        profile.setDescription("<script>alert('catalog')</script>");
        catalog.save("TABLE", table, profile, "dev");
        mvc.perform(get(asset.getPath()).with(user("reader").roles("VIEWER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("catalog-secret-marker"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("同名源表质量"))));
        assertThat(catalog.columns(catalog.require("MODEL", id("data_model")))).extracting(c -> c.name()).containsExactly("id");
    }

    @Test
    void keepsSameNamedTablesSeparateAcrossScopesAndCatalogs() throws Exception {
        seed();
        jdbc.update("INSERT INTO data_source(name,jdbc_url,username,encrypted_password) VALUES('业务库','jdbc:mysql://localhost/source','reader','unused')");
        long source = id("data_source");
        String scope = "source:" + source;
        jdbc.update("INSERT INTO metadata_scope(scope_key) VALUES(?)", scope);
        jdbc.update("INSERT INTO metadata_table(scope_key,catalog_name,table_name,comment,schema_hash) VALUES(?,'business','ods_orders','源表',REPEAT('b',64))", scope);
        long sourceTable = id("metadata_table");
        jdbc.update("INSERT INTO metadata_table(scope_key,catalog_name,table_name,comment,schema_hash) VALUES('doris','other_catalog','ods_orders','其他库表',REPEAT('c',64))");
        assertThat(catalog.stats()).isEqualTo(new AssetCatalogService.Stats(6, 3, 1, 1, 1));
        assertThat(catalog.require("TABLE", sourceTable).getSubjectArea()).isEmpty();
        assertThat(catalog.require("TABLE", id("metadata_table")).getSubjectArea()).isEmpty();
        assertThat(catalog.count(AssetFilters.from(Map.of("subjectArea", "交易")))).isEqualTo(3);
        jdbc.update("DELETE FROM data_source WHERE id=?", source);
        assertThat(catalog.require("TABLE", sourceTable).getStatus()).isEqualTo("MISSING");
    }

    private void seed() {
        jdbc.update("INSERT INTO metadata_scope(scope_key) VALUES('doris')");
        jdbc.update("INSERT INTO metadata_table(scope_key,catalog_name,table_name,comment,schema_hash) VALUES('doris','warehouse','ods_orders','订单表',REPEAT('a',64))");
        long table = id("metadata_table");
        jdbc.update("INSERT INTO metadata_column(table_id,ordinal_position,name,data_type,nullable,key_column,comment) VALUES(?,1,'id','bigint',FALSE,TRUE,'主键')", table);
        jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer,description) VALUES('ods_orders','订单模型','交易','ODS','订单数据')");
        long model = id("data_model");
        jdbc.update("INSERT INTO model_field(model_id,ordinal_position,name,data_type,key_column,nullable,comment) VALUES(?,1,'id','BIGINT',TRUE,FALSE,'主键')", model);
        var metric = new MetricDefinition();
        metric.setCode("order_count");
        metric.setName("订单数量");
        metric.setType(MetricType.ATOMIC);
        metric.setDescription("按订单主键计数");
        metric.setSubjectArea("交易");
        metric.setOwner("指标负责人");
        metric.setModelId(model);
        metric.setAggregation("COUNT");
        metric.setMeasureColumn("id");
        metrics.save(null, metric, "dev");
        jdbc.update("INSERT INTO data_service(name,slug,query_sql) VALUES('订单服务','orders','SELECT id FROM ods_orders ORDER BY id /* private-query-marker */')");
    }

    private long id(String table) {
        return jdbc.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
    }
}
