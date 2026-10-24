package com.litedata.store;

import com.litedata.metric.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MetricMapper {
    String FILTER = " WHERE archived=FALSE AND (#{type}='' OR type=#{type}) AND (#{keyword}='' OR LOCATE(#{keyword},LOWER(CONCAT(code,' ',name,' ',subject_area,' ',owner)))>0) ";
    @Select("SELECT id FROM metric_graph_guard WHERE id=1 FOR UPDATE") int lockGraph();
    @Select("SELECT COUNT(*) FROM metric_definition" + FILTER)
    long count(@Param("keyword") String keyword, @Param("type") String type);
    @Select("SELECT * FROM metric_definition" + FILTER + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<MetricDefinition> page(@Param("keyword") String keyword,@Param("type") String type,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT * FROM metric_definition WHERE id=#{id}") MetricDefinition metric(long id);
    @Select("SELECT * FROM metric_definition WHERE code=#{code} AND archived=FALSE") MetricDefinition byCode(String code);
    @Select("SELECT * FROM metric_definition WHERE archived=FALSE ORDER BY code") List<MetricDefinition> candidates();
    @Insert("INSERT INTO metric_definition(code,name,type,subject_area,owner,unit,description,dimensions,model_id,model_version,aggregation,measure_column,time_column,base_metric_id,window_days,filter_expression,formula,sql_text) VALUES(#{code},#{name},#{type},#{subjectArea},#{owner},#{unit},#{description},#{dimensions},#{modelId},#{modelVersion},#{aggregation},#{measureColumn},#{timeColumn},#{baseMetricId},#{windowDays},#{filterExpression},#{formula},#{sqlText})")
    @Options(useGeneratedKeys=true,keyProperty="id") void insert(MetricDefinition metric);
    @Update("UPDATE metric_definition SET name=#{name},subject_area=#{subjectArea},owner=#{owner},unit=#{unit},description=#{description},dimensions=#{dimensions},model_id=#{modelId},model_version=#{modelVersion},aggregation=#{aggregation},measure_column=#{measureColumn},time_column=#{timeColumn},base_metric_id=#{baseMetricId},window_days=#{windowDays},filter_expression=#{filterExpression},formula=#{formula},sql_text=#{sqlText},version=version+1 WHERE id=#{id}")
    void update(MetricDefinition metric);
    @Update("UPDATE metric_definition SET archived=TRUE,version=version+1 WHERE id=#{id}") void archive(long id);
    @Delete("DELETE FROM metric_dependency WHERE metric_id=#{id}") void clearDependencies(long id);
    @Insert("INSERT INTO metric_dependency(metric_id,upstream_id,upstream_version) VALUES(#{id},#{upstream},#{version})")
    void dependency(@Param("id") long id,@Param("upstream") long upstream,@Param("version") int version);
    @Select("SELECT upstream_id FROM metric_dependency WHERE metric_id=#{id}") List<Long> upstreams(long id);
    @Select("SELECT m.* FROM metric_dependency d JOIN metric_definition m ON m.id=d.metric_id WHERE d.upstream_id=#{id} AND m.archived=FALSE ORDER BY m.id")
    List<MetricDefinition> dependents(long id);
    @Select("SELECT COUNT(*) FROM metric_definition WHERE model_id=#{id} AND archived=FALSE") long modelReferences(long id);
    @Insert("INSERT INTO metric_version(metric_id,version,actor,definition_json,sql_text) VALUES(#{id},#{version},#{actor},#{json},#{sql})")
    void snapshot(@Param("id") long id,@Param("version") int version,@Param("actor") String actor,@Param("json") String json,@Param("sql") String sql);
    @Select("SELECT COUNT(*) FROM metric_version WHERE metric_id=#{id}") long versionCount(long id);
    @Select("SELECT * FROM metric_version WHERE metric_id=#{id} ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}")
    List<MetricVersion> versions(@Param("id") long id,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT * FROM metric_version WHERE metric_id=#{id} AND version=#{version}")
    MetricVersion version(@Param("id") long id,@Param("version") int version);
}
