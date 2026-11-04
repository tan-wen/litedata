package com.litedata.store;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ModelDependencyMapper {
    record SqlReference(String name, String sqlText) {}

    @Select("SELECT t.name,t.sql_text FROM sql_task t JOIN processing_workflow w ON w.id=t.workflow_id WHERE t.archived=FALSE AND w.archived=FALSE AND LOCATE(LOWER(#{table}),LOWER(t.sql_text))>0")
    List<SqlReference> tasks(String table);

    @Select("SELECT name,query_sql sql_text FROM data_service WHERE archived=FALSE AND LOCATE(LOWER(#{table}),LOWER(query_sql))>0")
    List<SqlReference> services(String table);

    @Select("SELECT COUNT(*) FROM quality_task t WHERE t.archived=FALSE AND t.source_id IS NULL AND (LOWER(t.table_name)=LOWER(#{table}) OR EXISTS(SELECT 1 FROM quality_rule r WHERE r.task_id=t.id AND r.type='REFERENCE' AND LOWER(r.reference_table)=LOWER(#{table})))")
    long qualityReferences(String table);

    @Select("SELECT CONCAT(t.name,' / ',r.name) name,r.parameter1 sql_text FROM quality_rule r JOIN quality_task t ON t.id=r.task_id WHERE t.archived=FALSE AND t.source_id IS NULL AND r.type='CUSTOM_SQL' AND LOCATE(LOWER(#{table}),LOWER(r.parameter1))>0")
    List<SqlReference> qualityQueries(String table);
}
