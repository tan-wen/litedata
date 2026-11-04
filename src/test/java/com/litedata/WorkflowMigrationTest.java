package com.litedata;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Exercises an actual populated V19 database, including legacy schedules created after its backfill. */
@Testcontainers
class WorkflowMigrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("upgrade").withUsername("upgrade").withPassword("upgrade-test-password");
    @Test void transfersSchedulesTopologyThenAssignsPrivateSqlOwnership() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).target("19").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        jdbc.update("INSERT INTO data_source(id,name,jdbc_url,username,encrypted_password) VALUES(1,'fixture','jdbc:mysql://fixture/source','fixture','encrypted-fixture')");
        jdbc.update("INSERT INTO pipeline(id,name,source_id,source_table,watermark_column,target_table,transform_sql,cron_expression) VALUES(1,'sync',1,'orders','updated_at','ods_orders','','0 */5 * * * ?')");
        for(int id=1;id<=5;id++) {
            jdbc.update("INSERT INTO data_model(id,table_name,name,subject_area,layer) VALUES(?,CONCAT('ads_',?),CONCAT('model-',?),'test','ADS')",id,id,id);
            jdbc.update("INSERT INTO sql_task(id,name,model_id,sql_text) VALUES(?,CONCAT('task-',?),?,'INSERT INTO ads_fixture SELECT id FROM ods_orders')",id,id,id);
        }
        jdbc.update("INSERT INTO sql_task_dependency(task_id,upstream_id) VALUES(2,1),(3,1),(4,2),(4,3),(5,1)");
        jdbc.update("INSERT INTO sql_pipeline_dependency(task_id,pipeline_id) VALUES(1,1),(5,1)");
        jdbc.update("UPDATE sql_task SET cron_expression='0 30 9 ? * MON-FRI',status='SCHEDULED',definition_code=701,definition_hash=REPEAT('a',64),schedule_id=91 WHERE id=4");
        jdbc.update("UPDATE sql_task SET cron_expression='0 0 2 L * ?',status='PAUSED',definition_code=702,definition_hash=REPEAT('b',64),schedule_id=92 WHERE id=2");
        jdbc.update("INSERT INTO processing_workflow(id,name,root_task_id,positions_json) VALUES(10,'existing-flow',4,'{}')");
        jdbc.update("INSERT INTO processing_workflow(id,name,root_task_id,positions_json) VALUES(11,'legacy-shared-flow',1,'{}')");
        jdbc.update("INSERT INTO processing_workflow_snapshot(definition_code,root_task_id,nodes_json) VALUES(701,4,'[]')");
        jdbc.update("INSERT INTO sql_task_run(task_id,root_task_id,task_version,target_table,sql_text) VALUES(1,4,1,'ads_1','fixture')");
        // Keep the original V19 -> V20 assertions separate from the ownership migration.
        // V22 intentionally changes how legacy SQL membership is represented.
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).target("20").load().migrate();

        assertThat(jdbc.queryForMap("SELECT cron_expression,status,definition_code,definition_hash,schedule_id,positions_json FROM processing_workflow WHERE id=10"))
                .containsEntry("cron_expression","0 30 9 ? * MON-FRI").containsEntry("status","SCHEDULED")
                .containsEntry("definition_code",701L).containsEntry("schedule_id",91).containsEntry("positions_json","{}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow_node WHERE workflow_id=10",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow_edge WHERE workflow_id=10",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_definition_snapshot WHERE workflow_id=10 AND definition_code=701",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT workflow_id FROM sql_task_run",Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow WHERE schedule_id=92 AND status='PAUSED' AND cron_expression='0 0 2 L * ?'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sql_task' AND column_name IN ('cron_expression','status','schedule_id','definition_code','definition_hash')",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='processing_workflow' AND column_name='root_task_id'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT graph_hash FROM workflow_legacy_binding WHERE workflow_id=10",String.class)).isEqualTo("a".repeat(64));

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sql_task WHERE workflow_id=10",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow_node n JOIN sql_task t ON t.id=n.task_id WHERE n.workflow_id=10 AND t.workflow_id<>n.workflow_id",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT review_required FROM processing_workflow WHERE id=11",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT ownership_notice FROM processing_workflow WHERE id=11",String.class)).contains("跨流程 SQL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_sql_ownership_migration WHERE workflow_id=11",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow_node n JOIN sql_task t ON t.id=n.task_id WHERE n.workflow_id=11 AND t.workflow_id<>n.workflow_id",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sql_task_dependency d JOIN sql_task t ON t.id=d.task_id JOIN sql_task u ON u.id=d.upstream_id WHERE t.workflow_id<>u.workflow_id",Integer.class)).isZero();
        long orphanOwner=jdbc.queryForObject("SELECT workflow_id FROM sql_task WHERE id=5",Long.class);
        assertThat(orphanOwner).isNotEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT review_required FROM processing_workflow WHERE id=?",Boolean.class,orphanOwner)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow_node WHERE workflow_id=?",Integer.class,orphanOwner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow_edge WHERE workflow_id=? AND from_key='pipeline-1' AND to_key='sql-5'",Integer.class,orphanOwner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(legacy_dependencies_json) FROM workflow_sql_ownership_migration WHERE workflow_id=?",Integer.class,orphanOwner)).isEqualTo(1);
        jdbc.update("INSERT INTO processing_workflow(name) VALUES('empty-draft')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_workflow WHERE name='empty-draft'",Integer.class)).isEqualTo(1);
    }
}
