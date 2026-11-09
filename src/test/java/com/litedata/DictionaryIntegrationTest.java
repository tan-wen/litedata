package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.catalog.*;
import com.litedata.dictionary.*;
import com.litedata.service.DataApiService;
import com.litedata.store.DictionaryMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DictionaryIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("platform").withUsername("platform").withPassword("dictionary-test-password");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("platform.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token", () -> "dictionary-test-internal-token-0123456789");
        registry.add("platform.admin-password", () -> "DictionaryTestPassword2026");
        registry.add("platform.doris.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("platform.doris.username", MYSQL::getUsername);
        registry.add("platform.doris.password", MYSQL::getPassword);
        registry.add("platform.quality.scheduling-enabled", () -> false);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DictionaryService dictionaries;
    @Autowired DictionaryMapper mapper;
    @Autowired DataApiService services;
    @Autowired AssetCatalogService catalog;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM asset_profile");
        jdbc.update("DELETE FROM api_credential");
        jdbc.update("DELETE FROM service_call");
        jdbc.update("DELETE FROM data_service");
        jdbc.update("DELETE FROM data_dictionary_item WHERE value<>'贴源接入' OR dictionary_code<>'SUBJECT_AREA'");
        jdbc.update("DELETE FROM data_dictionary WHERE code<>'SUBJECT_AREA'");
    }

    @Test
    void adminManagesIndustryDictionariesWithDatabasePaginationAndContext() throws Exception {
        mvc.perform(get("/dictionaries").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/dictionaries/SUBJECT_AREA/items/new").with(user("dev").roles("DEVELOPER"))).andExpect(status().isForbidden());
        mvc.perform(post("/dictionaries").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/dictionaries").with(user("admin").roles("ADMIN")).with(csrf())
                .param("code", "INDUSTRY").param("name", "行业类型").param("description", "行业标准分类"))
            .andExpect(status().is3xxRedirection());
        for (int i = 0; i < 3; i++)
            mvc.perform(post("/dictionaries/INDUSTRY/items").with(user("admin").roles("ADMIN")).with(csrf())
                    .param("value", "ITEM_" + i).param("label", "行业" + i).param("sortOrder", Integer.toString(3 - i)).param("enabled", "true"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/dictionaries/INDUSTRY/items").param("pageNum", "99").param("pageSize", "1").param("keyword", "行业")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 3L)).andExpect(model().attribute("pageNum", 3));
        var item = mapper.choices("INDUSTRY").getFirst();
        assertThat(item.getValue()).isEqualTo("ITEM_2");
        mvc.perform(get("/dictionaries/INDUSTRY/items/" + item.getId() + "/edit").param("pageNum", "2").param("pageSize", "1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
            .andExpect(model().attribute("listReturnPath", "/dictionaries/INDUSTRY/items?pageNum=2&pageSize=1&keyword="));
        mvc.perform(post("/dictionaries/INDUSTRY/items/" + item.getId()).with(user("admin").roles("ADMIN")).with(csrf())
                .param("value", item.getValue()).param("label", "更新行业").param("version", "1").param("sortOrder", "0").param("enabled", "true")
                .param("pageNum", "2").param("pageSize", "1"))
            .andExpect(redirectedUrl("/dictionaries/INDUSTRY/items?pageNum=2&pageSize=1&keyword="));
        mvc.perform(post("/dictionaries/INDUSTRY/items/" + item.getId()).with(user("admin").roles("ADMIN")).with(csrf())
                .param("value", item.getValue()).param("label", "过期输入").param("version", "1").param("sortOrder", "0"))
            .andExpect(status().isOk()).andExpect(model().attributeExists("error"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("过期输入")));
        assertThat(mapper.item("INDUSTRY", item.getId()).getLabel()).isEqualTo("更新行业");
        mvc.perform(post("/dictionaries/SUBJECT_AREA/delete").param("version", "1").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    void serviceMetadataUsesStableDictionaryBindingsAndRetainsPublicationAndCredentials() throws Exception {
        var item = topic("PRODUCTION", "生产制造");
        mvc.perform(post("/services").with(user("dev").roles("DEVELOPER")).with(csrf()).param("name", "产量接口")
                .param("slug", "production").param("querySql", "SELECT id FROM warehouse.orders ORDER BY id").param("subjectArea", "PRODUCTION")
                .param("description", "供生产驾驶舱查询产量，按日汇总。"))
            .andExpect(redirectedUrl("/services"));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM data_service", Long.class);
        services.publish(id);
        String token = services.credential(id);
        String digest = DataApiService.hash(token);
        item.setLabel("制造业务"); item.setVersion(1);
        dictionaries.saveItem("SUBJECT_AREA", item.getId(), item, "admin");
        mvc.perform(get("/services").with(user("reader").roles("VIEWER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("制造业务")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("供生产驾驶舱查询产量")));
        mvc.perform(get("/assets").param("assetType", "SERVICE").with(user("reader").roles("VIEWER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("制造业务")));
        mvc.perform(get("/assets").param("assetType", "SERVICE").param("keyword", "制造业务").with(user("reader").roles("VIEWER")))
            .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 1L));
        mvc.perform(post("/services/" + id).with(user("dev").roles("DEVELOPER")).with(csrf()).param("name", "产量接口")
                .param("slug", "production").param("querySql", "SELECT id FROM warehouse.orders ORDER BY id").param("subjectArea", "PRODUCTION")
                .param("description", "用途更新").param("pageNum", "2").param("pageSize", "10"))
            .andExpect(redirectedUrl("/services?pageNum=2&pageSize=10"));
        assertThat(services.get(id).isPublished()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_credential WHERE service_id=? AND token_hash=? AND enabled=TRUE", Integer.class, id, digest)).isEqualTo(1);
        var profile = new AssetProfile(); profile.setSubjectArea("PRODUCTION"); profile.setDescription("资产目录维护的同一份接口用途");
        catalog.save("SERVICE", id, profile, "dev");
        assertThat(services.get(id).getDescription()).isEqualTo(profile.getDescription());
        assertThat(jdbc.queryForObject("SELECT description FROM asset_profile WHERE asset_type='SERVICE' AND resource_id=?", String.class, id)).isEmpty();
        assertThat(services.get(id).isPublished()).isTrue();
        services.update(id, "产量接口", "production", "SELECT id FROM warehouse.orders WHERE id>0 ORDER BY id", "PRODUCTION", "用途更新");
        assertThat(services.get(id).isPublished()).isFalse();
    }

    @Test
    void inactiveAndUnknownTopicsCannotBeNewBindingsAndReferencedItemsCannotBeDeleted() throws Exception {
        var item = topic("CUSTOMER", "客户业务");
        services.create("客户接口", "customer", "SELECT id FROM warehouse.orders ORDER BY id", "CUSTOMER", "客户查询用途");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM data_service", Long.class);
        assertThatIllegalArgumentException().isThrownBy(() -> dictionaries.deleteItem("SUBJECT_AREA", item.getId(), 1, "admin")).withMessageContaining("已被资产使用");
        assertThatIllegalArgumentException().isThrownBy(() -> dictionaries.deleteItem("subject_area", item.getId(), 1, "admin")).withMessageContaining("已被资产使用");
        assertThatIllegalArgumentException().isThrownBy(() -> dictionaries.delete("subject_area", 1, "admin")).withMessageContaining("不可删除");
        item.setVersion(1); item.setEnabled(false);
        dictionaries.saveItem("SUBJECT_AREA", item.getId(), item, "admin");
        services.update(id, "客户接口", "customer", "SELECT id FROM warehouse.orders ORDER BY id", "CUSTOMER", "保留原停用绑定");
        assertThatIllegalArgumentException().isThrownBy(() -> services.create("新接口", "new-customer", "SELECT id FROM warehouse.orders ORDER BY id", "CUSTOMER", "")).withMessageContaining("已启用");
        assertThatIllegalArgumentException().isThrownBy(() -> services.create("新接口", "unknown", "SELECT id FROM warehouse.orders ORDER BY id", "UNKNOWN", "")).withMessageContaining("已启用");
        mvc.perform(get("/services/" + id + "/edit").with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("客户业务（已停用）")));
        mvc.perform(post("/services").with(user("dev").roles("DEVELOPER")).with(csrf()).param("name", "保留输入")
                .param("slug", "invalid-topic").param("querySql", "SELECT id FROM warehouse.orders ORDER BY id").param("subjectArea", "UNKNOWN").param("description", "保留用途描述"))
            .andExpect(status().isOk()).andExpect(model().attributeExists("error"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("保留用途描述")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_service", Integer.class)).isEqualTo(1);
        var unused = topic("UNUSED", "未使用主题");
        dictionaries.deleteItem("SUBJECT_AREA", unused.getId(), 1, "admin");
        assertThat(mapper.choices("SUBJECT_AREA")).noneMatch(choice -> choice.getValue().equals("UNUSED"));
    }

    private DictionaryItem topic(String value, String label) {
        var draft = new DictionaryItem(); draft.setValue(value); draft.setLabel(label);
        dictionaries.saveItem("SUBJECT_AREA", null, draft, "admin");
        return mapper.choices("SUBJECT_AREA").stream().filter(item -> item.getValue().equals(value)).findFirst().orElseThrow();
    }
}
