package com.litedata.store;

import com.litedata.model.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ModelMapper {
    @Select("SELECT * FROM data_model WHERE table_name=#{name} AND archived=FALSE")
    DataModel modelByTable(String name);
    @Select("SELECT * FROM data_model WHERE archived=FALSE ORDER BY id DESC")
    List<DataModel> models();
    @Select("SELECT COUNT(*) FROM data_model WHERE archived=FALSE AND (#{layer}='' OR layer=#{layer}) AND (#{keyword}='' OR LOCATE(#{keyword}, LOWER(CONCAT(table_name, ' ', name, ' ', subject_area)))>0)")
    long countModels(@Param("keyword") String keyword, @Param("layer") String layer);
    @Select("SELECT * FROM data_model WHERE archived=FALSE AND (#{layer}='' OR layer=#{layer}) AND (#{keyword}='' OR LOCATE(#{keyword}, LOWER(CONCAT(table_name, ' ', name, ' ', subject_area)))>0) ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<DataModel> modelPage(@Param("keyword") String keyword, @Param("layer") String layer, @Param("limit") int limit, @Param("offset") long offset);
    @Select("SELECT * FROM data_model WHERE id=#{id} AND archived=FALSE")
    DataModel model(long id);
    @Select("SELECT * FROM data_model WHERE id=#{id} AND archived=FALSE FOR UPDATE")
    DataModel lockModel(long id);
    @Select("SELECT * FROM model_field WHERE model_id=#{id} ORDER BY ordinal_position")
    List<ModelField> fields(long id);
    @Insert("INSERT INTO data_model(table_name,name,subject_area,layer,description,buckets,dimension_keys,dimension_label,dimension_strategy) VALUES(#{tableName},#{name},#{subjectArea},#{layer},#{description},#{buckets},#{dimensionKeys},#{dimensionLabel},#{dimensionStrategy})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    void insert(DataModel model);
    @Update("UPDATE data_model SET name=#{name},subject_area=#{subjectArea},layer=#{layer},description=#{description},buckets=#{buckets},dimension_keys=#{dimensionKeys},dimension_label=#{dimensionLabel},dimension_strategy=#{dimensionStrategy},version=version+1 WHERE id=#{id}")
    void update(DataModel model);
    @Insert("INSERT INTO model_field(model_id,ordinal_position,name,data_type,key_column,nullable,comment,standard) VALUES(#{id},#{position},#{field.name},#{field.dataType},#{field.keyColumn},#{field.nullable},#{field.comment},#{field.standard})")
    void insertField(@Param("id") long id,@Param("position") int position,@Param("field") ModelField field);
    @Delete("DELETE FROM model_field WHERE model_id=#{id}")
    void deleteFields(long id);
    @Update("UPDATE data_model SET archived=TRUE WHERE id=#{id}")
    void archive(long id);
    @Update("UPDATE data_model SET physical_version=#{version} WHERE id=#{id}")
    void physicalVersion(@Param("id") long id,@Param("version") Integer version);
    @Insert("INSERT INTO model_execution(model_id,model_version,operation,target_table,ddl,actor,status,message) VALUES(#{modelId},#{modelVersion},#{operation},#{targetTable},#{ddl},#{actor},#{status},#{message})")
    void execution(ModelExecution execution);
    @Select("SELECT * FROM model_execution WHERE model_id=#{id} ORDER BY id DESC LIMIT 30")
    List<ModelExecution> executions(long id);
}
