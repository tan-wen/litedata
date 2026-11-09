package com.litedata;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DictionaryMigrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("upgrade").withUsername("upgrade").withPassword("dictionary-upgrade-test-password");
    @Test
    void preservesExistingIndustryTopicsAndMovesServiceMetadataWithoutChangingPublication() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("25").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("INSERT INTO data_model(table_name,name,subject_area,layer) VALUES('ods_orders','订单模型','行业交易','ODS')");
        jdbc.update("INSERT INTO metric_definition(code,name,type,subject_area,owner,sql_text) VALUES('count','订单量','ATOMIC','行业交易','dev','SELECT 1')");
        jdbc.update("INSERT INTO data_service(id,name,slug,query_sql,published) VALUES(10,'客户查询','customers','SELECT 1',TRUE)");
        jdbc.update("INSERT INTO asset_profile(asset_type,resource_id,subject_area,description,owner,updated_by) VALUES('SERVICE',10,'客户经营','提供客户经营分析数据','接口负责人','migration-test')");
        jdbc.update("INSERT INTO asset_profile(asset_type,resource_id,subject_area,description,updated_by) VALUES('MODEL',1,'主题覆盖','模型业务说明','migration-test')");
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        assertThat(jdbc.queryForList("SELECT value FROM data_dictionary_item WHERE dictionary_code='SUBJECT_AREA'", String.class))
            .containsExactlyInAnyOrder("贴源接入", "行业交易", "客户经营", "主题覆盖");
        assertThat(jdbc.queryForObject("SELECT subject_area FROM data_service WHERE id=10", String.class)).isEqualTo("客户经营");
        assertThat(jdbc.queryForObject("SELECT description FROM data_service WHERE id=10", String.class)).isEqualTo("提供客户经营分析数据");
        assertThat(jdbc.queryForObject("SELECT published FROM data_service WHERE id=10", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT subject_area FROM asset_profile WHERE asset_type='SERVICE' AND resource_id=10", String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT owner FROM asset_profile WHERE asset_type='SERVICE' AND resource_id=10", String.class)).isEqualTo("接口负责人");
        assertThat(jdbc.queryForObject("SELECT subject_area FROM asset_profile WHERE asset_type='MODEL' AND resource_id=1", String.class)).isEqualTo("主题覆盖");
    }
}
