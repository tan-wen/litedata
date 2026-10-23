package com.litedata.store;

import com.litedata.governance.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MetadataMapper {
    String JOIN = " FROM metadata_table t LEFT JOIN data_source s ON t.scope_key=CONCAT('source:',s.id) LEFT JOIN data_model m ON t.scope_key='doris' AND LOWER(t.table_name)=m.table_name AND m.archived=FALSE ";
    String SELECT = "SELECT t.*,FLOOR(UNIX_TIMESTAMP(t.collected_at)) collected_epoch,CASE WHEN t.scope_key='doris' THEN 'Doris 数仓' ELSE s.name END source_name,m.id model_id,m.name model_name,m.layer" + JOIN;
    String FILTER = " WHERE (#{layer}='' OR m.layer=#{layer}) AND (#{keyword}='' OR LOCATE(#{keyword},LOWER(CONCAT(t.catalog_name,'.',t.table_name,' ',t.comment,' ',COALESCE(s.name,''),' ',COALESCE(m.name,''))))>0) ";
    @Insert("INSERT IGNORE INTO metadata_scope(scope_key) VALUES(#{scope})") void ensureScope(String scope);
    @Select("SELECT scope_key FROM metadata_scope WHERE scope_key=#{scope} FOR UPDATE") String lockScope(String scope);
    @Select("SELECT COUNT(*)" + JOIN + FILTER) long count(@Param("keyword") String keyword,@Param("layer") String layer);
    @Select(SELECT + FILTER + " ORDER BY t.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<MetadataTable> page(@Param("keyword") String keyword,@Param("layer") String layer,@Param("limit") int limit,@Param("offset") long offset);
    @Select(SELECT + " WHERE t.id=#{id}") MetadataTable table(long id);
    @Select("SELECT * FROM metadata_table WHERE scope_key=#{scope} AND catalog_name=#{catalog} AND table_name=#{name}")
    MetadataTable find(@Param("scope") String scope,@Param("catalog") String catalog,@Param("name") String name);
    @Select("SELECT name,data_type,nullable,key_column,comment,ordinal_position position FROM metadata_column WHERE table_id=#{id} ORDER BY ordinal_position")
    List<JdbcMetadataCollector.Column> columns(long id);
    @Update("UPDATE metadata_table SET present=FALSE WHERE scope_key=#{scope}") void markMissing(String scope);
    @Insert("INSERT INTO metadata_table(scope_key,catalog_name,table_name,comment,schema_hash) VALUES(#{scopeKey},#{catalogName},#{tableName},#{comment},#{schemaHash})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insert(MetadataTable table);
    @Update("UPDATE metadata_table SET comment=#{comment},version=version+IF(schema_hash<>#{schemaHash},1,0),schema_hash=#{schemaHash},present=TRUE,collected_at=CURRENT_TIMESTAMP(6) WHERE id=#{id}")
    void update(MetadataTable table);
    @Delete("DELETE FROM metadata_column WHERE table_id=#{id}") void deleteColumns(long id);
    @Insert("INSERT INTO metadata_column(table_id,ordinal_position,name,data_type,nullable,key_column,comment) VALUES(#{id},#{c.position},#{c.name},#{c.dataType},#{c.nullable},#{c.keyColumn},#{c.comment})")
    void insertColumn(@Param("id") long id,@Param("c") JdbcMetadataCollector.Column column);
}
