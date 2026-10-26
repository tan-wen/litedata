package com.litedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.litedata.governance.*;
import com.litedata.governance.JdbcMetadataCollector.*;
import com.litedata.store.MetadataMapper;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Real MySQL persistence, migrations and MVC; schema reading is mocked separately from the JDBC collector tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MetadataIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("platform").withUsername("platform").withPassword("metadata-test-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl); registry.add("spring.datasource.username",MYSQL::getUsername);
        registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("platform.encryption-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("platform.internal-token",()->"metadata-test-internal-token-0123456789");
        registry.add("platform.admin-password",()->"MetadataAdminPassword2026");
        registry.add("platform.doris.jdbc-url",MYSQL::getJdbcUrl); registry.add("platform.doris.username",MYSQL::getUsername);
        registry.add("platform.doris.password",MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetadataService service;
    @Autowired MetadataMapper mapper;
    @Autowired LineageService lineage;
    @MockitoBean JdbcMetadataCollector collector;
    @BeforeEach void clean() {
        for(String table:List.of("metadata_column","metadata_table","metadata_scope","sql_task_run","sql_task_dependency","sql_pipeline_dependency","sql_task","model_execution","model_field","data_model","pipeline_run","pipeline","data_source","audit_event")) jdbc.update("DELETE FROM "+table);
        reset(collector);
    }
    @Test void collectsVersionedSnapshotsAndKeepsMissingAndFailedStructures() throws Exception {
        when(collector.collect(null)).thenReturn(snapshot("warehouse","ods_orders","ads_orders"));
        assertThat(service.collect(null,"dev")).isEqualTo(2);
        var table=mapper.find("doris","warehouse","ods_orders"); long id=table.getId();
        assertThat(service.require(id).getCollectedAt()).isCloseTo(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),within(1,java.time.temporal.ChronoUnit.MINUTES));
        assertThat(mapper.columns(id)).extracting(Column::name).containsExactly("id");
        service.collect(null,"dev"); assertThat(service.require(id).getVersion()).isEqualTo(1);
        when(collector.collect(null)).thenReturn(new Snapshot("warehouse",List.of(new Table("ods_orders","changed",List.of(new Column("total","decimal(18,2)",true,false,"金额",1))))));
        service.collect(null,"dev");
        assertThat(service.require(id).getVersion()).isEqualTo(2);
        assertThat(mapper.columns(id)).extracting(Column::name).containsExactly("total");
        assertThat(mapper.find("doris","warehouse","ads_orders").isPresent()).isFalse();
        when(collector.collect(null)).thenThrow(new SQLException("password=never-expose-driver-message"));
        assertThatIllegalArgumentException().isThrownBy(()->service.collect(null,"dev")).withMessageNotContaining("never-expose");
        assertThat(service.require(id).isPresent()).isTrue();
        assertThat(service.require(id).getVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='METADATA_COLLECT'",Integer.class)).isEqualTo(3);
    }
    @Test void failedPersistenceRollsBackTheWholeScope() throws Exception {
        when(collector.collect(null)).thenReturn(snapshot("warehouse","ods_orders")); service.collect(null,"dev");
        long id=mapper.find("doris","warehouse","ods_orders").getId();
        var duplicate=new Table("broken","",List.of(new Column("a","int",false,false,"",1),new Column("b","int",false,false,"",1)));
        when(collector.collect(null)).thenReturn(new Snapshot("warehouse",List.of(duplicate)));
        assertThatThrownBy(()->service.collect(null,"dev")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.require(id).isPresent()).isTrue();
        assertThat(mapper.find("doris","warehouse","broken")).isNull();
    }

    @Test void dimensionMetadataSupportsLayerFilteringAndLineage() throws Exception {
        when(collector.collect(null)).thenReturn(snapshot("warehouse", "ods_enterprise", "dim_enterprise", "dwd_usage"));
        service.collect(null, "dev");
        task("dim_enterprise", "INSERT INTO dim_enterprise SELECT id FROM ods_enterprise");
        jdbc.update("UPDATE data_model SET layer='DIM', dimension_keys='id', dimension_strategy='SCD1' WHERE table_name='dim_enterprise'");
        task("dwd_usage", "INSERT INTO dwd_usage SELECT id FROM dim_enterprise");
        mvc.perform(get("/metadata").param("layer", "DIM").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 1L))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("dim_enterprise")));
        long id = mapper.find("doris", "warehouse", "dim_enterprise").getId();
        var graph = lineage.graph(id, "both", 3);
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.nodes()).extracting(LineageService.Node::table).contains("warehouse.ods_enterprise", "warehouse.dim_enterprise", "warehouse.dwd_usage");
    }
    @Test void paginatesFiltersAndRendersAllGovernancePages() throws Exception {
        when(collector.collect(null)).thenReturn(snapshot("warehouse","ods_orders","ads_orders","ods_customers")); service.collect(null,"dev");
        long id=mapper.find("doris","warehouse","ods_orders").getId();
        jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer) VALUES('ods_orders','订单模型','交易','ODS')");
        mvc.perform(get("/metadata").param("keyword","ods_").param("pageSize","1").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(model().attribute("totalCount",2L)).andExpect(model().attribute("hasMore",true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("keyword=ods_")));
        mvc.perform(get("/metadata").param("pageNum","999").param("pageSize","2").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(model().attribute("pageNum",2));
        mvc.perform(get("/metadata").param("keyword","订单").param("layer","ODS").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk()).andExpect(model().attribute("totalCount",1L));
        for(String path:List.of("/metadata/collect","/metadata/"+id,"/metadata/"+id+"/lineage"))
            mvc.perform(get(path).with(user("dev").roles("DEVELOPER"))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        for(String path:List.of("/metadata?pageNum=0","/metadata?layer=BAD","/metadata/"+id+"/lineage?depth=7","/metadata/"+id+"/lineage?direction=bad"))
            mvc.perform(get(path).with(user("dev").roles("DEVELOPER"))).andExpect(status().isBadRequest());
        mvc.perform(get("/metadata/9999999").with(user("dev").roles("DEVELOPER"))).andExpect(status().isNotFound());
    }
    @Test void enforcesAuthenticationRolesCsrfAndRedactsCollectionErrors() throws Exception {
        mvc.perform(get("/metadata")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/metadata").with(user("reader").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">采集元数据</a>"))));
        mvc.perform(get("/metadata/collect").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/metadata/collect").with(user("reader").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/metadata/collect").with(user("dev").roles("DEVELOPER"))).andExpect(status().isForbidden());
        verifyNoInteractions(collector);
        when(collector.collect(null)).thenThrow(new SQLException("secret-driver-password"));
        mvc.perform(post("/metadata/collect").with(user("dev").roles("DEVELOPER")).with(csrf()))
                .andExpect(redirectedUrl("/metadata")).andExpect(flash().attribute("error",org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-driver"))));
    }
    @Test void tracesRealTableReadsWithoutConfusingSameNamedSourcesOrSchedulingEdges() throws Exception {
        jdbc.update("INSERT INTO data_source(name,jdbc_url,username,encrypted_password) VALUES('业务库','jdbc:mysql://localhost/business','reader','unused')");
        long source=jdbc.queryForObject("SELECT id FROM data_source",Long.class);
        when(collector.collect(null)).thenReturn(snapshot("warehouse","ods_orders","dwd_orders","ads_orders","isolated")); service.collect(null,"dev");
        when(collector.collect(source)).thenReturn(snapshot("business","ods_orders")); service.collect(source,"dev");
        jdbc.update("INSERT INTO pipeline(name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) VALUES('订单同步',?,'ods_orders','updated_at','ods_orders','INSERT INTO dwd_orders SELECT id FROM ods_orders','0 0 * * * ?')",source);
        task("ads_orders","INSERT INTO ads_orders WITH chosen AS (SELECT id FROM dwd_orders) SELECT id FROM chosen");
        long root=mapper.find("doris","warehouse","dwd_orders").getId();
        var graph=lineage.graph(root,"both",3);
        assertThat(graph.edges()).hasSize(3); assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes()).extracting(LineageService.Node::key).contains("source:"+source+"/business/ods_orders","doris/warehouse/ods_orders");
        assertThat(graph.nodes()).extracting(LineageService.Node::table).doesNotContain("warehouse.chosen","warehouse.isolated");
        assertThat(lineage.graph(root,"upstream",1).nodes()).hasSize(2);
        assertThat(lineage.graph(root,"downstream",1).nodes()).hasSize(2);
        mvc.perform(get("/metadata/"+root+"/lineage").with(user("reader").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("订单同步")));
        jdbc.update("UPDATE sql_task SET archived=TRUE");
        assertThat(lineage.graph(root,"downstream",3).edges()).isEmpty();
    }
    @Test void reportsParseFailuresAndUncollectedNodesAndTerminatesOnCycles() throws Exception {
        when(collector.collect(null)).thenReturn(snapshot("warehouse","ods_orders")); service.collect(null,"dev");
        task("dwd_orders","INSERT INTO dwd_orders SELECT id FROM ods_orders");
        task("ads_orders","not parseable");
        task("ods_orders","INSERT INTO ods_orders SELECT id FROM dwd_orders");
        long root=mapper.find("doris","warehouse","ods_orders").getId();
        var graph=lineage.graph(root,"both",6);
        assertThat(graph.nodes()).hasSize(2); assertThat(graph.edges()).hasSize(2);
        assertThat(graph.nodes().stream().filter(n->n.table().equals("warehouse.dwd_orders")).findFirst().orElseThrow().metadataId()).isNull();
        assertThat(graph.warnings()).hasSize(1);
        mvc.perform(get("/metadata/"+root+"/lineage").with(user("reader").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("解析失败")));
    }
    private void task(String target,String sql) {
        jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer) VALUES(?,?,'交易','ADS')",target,target);
        long model=jdbc.queryForObject("SELECT id FROM data_model WHERE table_name=?",Long.class,target);
        jdbc.update("INSERT INTO sql_task(name,model_id,sql_text) VALUES(?,?,?)",target,model,sql);
    }
    private static Snapshot snapshot(String catalog,String... names) {
        return new Snapshot(catalog,Arrays.stream(names).map(name->new Table(name,"表注释",List.of(new Column("id","bigint",false,true,"业务主键",1)))).toList());
    }
}
