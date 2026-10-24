package com.litedata.store;

import com.litedata.catalog.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AssetCatalogMapper {
    // The inventory is live: annotations never duplicate or drive source-resource lifecycle.
    String INVENTORY="""
        SELECT 'TABLE' asset_type,t.id resource_id,t.table_name name,CONCAT(t.catalog_name,'.',t.table_name) code,
          COALESCE(m.subject_area,'') subject_area,'' owner,t.comment description,COALESCE(m.layer,'') layer,
          CASE WHEN t.scope_key='doris' THEN 'Doris 数仓' ELSE COALESCE(s.name,'来源已移除') END source_name,
          CASE WHEN t.present=FALSE OR (t.scope_key<>'doris' AND s.id IS NULL) THEN 'MISSING' ELSE 'ACTIVE' END status,
          t.version resource_version,t.id metadata_id,FLOOR(UNIX_TIMESTAMP(t.collected_at)) collected_epoch,t.scope_key,t.table_name
        FROM metadata_table t LEFT JOIN data_source s ON t.scope_key=CONCAT('source:',s.id)
          LEFT JOIN data_model m ON t.scope_key='doris' AND t.catalog_name='warehouse' AND LOWER(t.table_name)=m.table_name AND m.archived=FALSE
        UNION ALL
        SELECT 'MODEL',m.id,m.name,m.table_name,m.subject_area,'',m.description,m.layer,'Doris 数仓',
          CASE WHEN m.archived THEN 'ARCHIVED' ELSE 'ACTIVE' END,m.version,t.id,FLOOR(UNIX_TIMESTAMP(t.collected_at)),'doris',m.table_name
        FROM data_model m LEFT JOIN metadata_table t ON t.scope_key='doris' AND t.catalog_name='warehouse' AND LOWER(t.table_name)=m.table_name
        UNION ALL
        SELECT 'METRIC',d.id,d.name,d.code,d.subject_area,d.owner,d.description,'','指标管理',
          CASE WHEN d.archived THEN 'ARCHIVED' ELSE 'ACTIVE' END,d.version,NULL,NULL,'',''
        FROM metric_definition d
        UNION ALL
        SELECT 'SERVICE',s.id,s.name,s.slug,'','','','','数据服务',
          CASE WHEN s.published THEN 'PUBLISHED' ELSE 'UNPUBLISHED' END,0,NULL,NULL,'',''
        FROM data_service s
        """;
    String ENTRIES="""
        SELECT a.asset_type,a.resource_id,COALESCE(NULLIF(p.business_name,''),a.name) name,a.code,
          COALESCE(NULLIF(p.subject_area,''),a.subject_area) subject_area,
          COALESCE(NULLIF(p.owner,''),a.owner) owner,COALESCE(NULLIF(p.description,''),a.description) description,
          a.layer,a.source_name,a.status,a.resource_version,a.metadata_id,a.collected_epoch,a.scope_key,a.table_name,
          COALESCE(p.tags,'') tags,COALESCE(p.update_frequency,'') update_frequency,COALESCE(p.sensitivity,'INTERNAL') sensitivity
        FROM (
        """+INVENTORY+") a LEFT JOIN asset_profile p ON p.asset_type=a.asset_type AND p.resource_id=a.resource_id ";
    String FROM=" FROM ("+ENTRIES+") a ";
    String FILTER="""
        WHERE (#{f.assetType}='' OR a.asset_type=#{f.assetType})
          AND (#{f.subjectArea}='' OR a.subject_area=#{f.subjectArea})
          AND (#{f.owner}='' OR a.owner=#{f.owner})
          AND (#{f.status}='' OR a.status=#{f.status})
          AND (#{f.tag}='' OR FIND_IN_SET(#{f.tag},a.tags)>0)
          AND (#{f.keyword}='' OR LOCATE(#{f.keyword},LOWER(CONCAT_WS(' ',a.name,a.code,a.subject_area,a.owner,a.description,a.tags,a.source_name)))>0)
        """;
    String COLUMNS="SELECT a.*,a.asset_type type ";
    @Select("SELECT COUNT(*)"+FROM+FILTER) long count(@Param("f") AssetFilters filters);
    @Select(COLUMNS+FROM+FILTER+" ORDER BY a.asset_type,a.resource_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<AssetEntry> page(@Param("f") AssetFilters filters,@Param("limit") int limit,@Param("offset") long offset);
    @Select(COLUMNS+FROM+" WHERE a.asset_type=#{type} AND a.resource_id=#{id}")
    AssetEntry entry(@Param("type") AssetType type,@Param("id") long id);
    @Select("SELECT COUNT(*) total,COALESCE(SUM(asset_type='TABLE'),0) tables,COALESCE(SUM(asset_type='MODEL'),0) models,COALESCE(SUM(asset_type='METRIC'),0) metrics,COALESCE(SUM(asset_type='SERVICE'),0) services FROM ("+INVENTORY+") a")
    AssetCatalogService.Stats stats();
    @Select("SELECT *,FLOOR(UNIX_TIMESTAMP(updated_at)) updated_epoch FROM asset_profile WHERE asset_type=#{type} AND resource_id=#{id}")
    AssetProfile profile(@Param("type") AssetType type,@Param("id") long id);
    @Insert("""
        INSERT INTO asset_profile(asset_type,resource_id,business_name,subject_area,owner,description,tags,update_frequency,sensitivity,updated_by)
        VALUES(#{type},#{id},#{p.businessName},#{p.subjectArea},#{p.owner},#{p.description},#{p.tags},#{p.updateFrequency},#{p.sensitivity},#{actor})
        """)
    void insert(@Param("type") AssetType type,@Param("id") long id,@Param("p") AssetProfile profile,@Param("actor") String actor);
    @Update("""
        UPDATE asset_profile SET business_name=#{p.businessName},subject_area=#{p.subjectArea},owner=#{p.owner},description=#{p.description},
          tags=#{p.tags},update_frequency=#{p.updateFrequency},sensitivity=#{p.sensitivity},updated_by=#{actor},version=version+1
        WHERE asset_type=#{type} AND resource_id=#{id} AND version=#{p.version}
        """)
    int update(@Param("type") AssetType type,@Param("id") long id,@Param("p") AssetProfile profile,@Param("actor") String actor);
    String QUALITY_FROM="""
        FROM quality_task q WHERE q.archived=FALSE AND q.table_name=#{table}
          AND ((#{scope}='doris' AND q.source_id IS NULL) OR CONCAT('source:',q.source_id)=#{scope})
        """;
    @Select("SELECT COUNT(*) "+QUALITY_FROM)
    long qualityCount(@Param("scope") String scope,@Param("table") String table);
    @Select("SELECT q.id,q.name,COALESCE((SELECT r.status FROM quality_run r WHERE r.task_id=q.id ORDER BY r.id DESC LIMIT 1),'NOT_RUN') status "+QUALITY_FROM+" ORDER BY q.id DESC LIMIT 10")
    List<AssetCatalogService.QualityLink> qualityLinks(@Param("scope") String scope,@Param("table") String table);
}
