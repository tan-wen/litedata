package com.litedata.store;

import com.litedata.dictionary.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DictionaryMapper {
    String FILTER = " WHERE (#{keyword}='' OR LOCATE(#{keyword},LOWER(CONCAT(code,' ',name,' ',description)))>0) ";
    @Select("SELECT COUNT(*) FROM data_dictionary" + FILTER)
    long count(String keyword);
    @Select("SELECT * FROM data_dictionary" + FILTER + " ORDER BY code LIMIT #{limit} OFFSET #{offset}")
    List<DictionaryDefinition> page(@Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset);
    @Select("SELECT * FROM data_dictionary WHERE code=#{code}")
    DictionaryDefinition definition(String code);
    @Insert("INSERT INTO data_dictionary(code,name,description) VALUES(#{code},#{name},#{description})")
    void insert(DictionaryDefinition input);
    @Update("UPDATE data_dictionary SET name=#{name},description=#{description},version=version+1 WHERE code=#{code} AND version=#{version}")
    int update(DictionaryDefinition input);
    @Select("SELECT * FROM data_dictionary WHERE code=#{code} FOR UPDATE")
    DictionaryDefinition lockDefinition(String code);
    @Delete("DELETE FROM data_dictionary WHERE code=#{code}")
    void deleteDefinition(String code);
    String ITEM_FILTER = " WHERE dictionary_code=#{code} AND (#{keyword}='' OR LOCATE(#{keyword},LOWER(CONCAT(value,' ',label,' ',description)))>0) ";
    @Select("SELECT COUNT(*) FROM data_dictionary_item" + ITEM_FILTER)
    long itemCount(@Param("code") String code, @Param("keyword") String keyword);
    @Select("SELECT * FROM data_dictionary_item" + ITEM_FILTER + " ORDER BY sort_order,id LIMIT #{limit} OFFSET #{offset}")
    List<DictionaryItem> itemPage(@Param("code") String code, @Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") long offset);
    @Select("SELECT * FROM data_dictionary_item WHERE dictionary_code=#{code} ORDER BY sort_order,id")
    List<DictionaryItem> choices(String code);
    @Select("SELECT * FROM data_dictionary_item WHERE dictionary_code=#{code} AND id=#{id}")
    DictionaryItem item(@Param("code") String code, @Param("id") long id);
    @Select("SELECT * FROM data_dictionary_item WHERE dictionary_code=#{code} AND id=#{id} FOR UPDATE")
    DictionaryItem lockItem(@Param("code") String code, @Param("id") long id);
    @Select("SELECT * FROM data_dictionary_item WHERE dictionary_code=#{code} AND value=#{value} FOR UPDATE")
    DictionaryItem lockValue(@Param("code") String code, @Param("value") String value);
    @Insert("INSERT INTO data_dictionary_item(dictionary_code,value,label,description,sort_order,enabled) VALUES(#{dictionaryCode},#{value},#{label},#{description},#{sortOrder},#{enabled})")
    void insertItem(DictionaryItem input);
    @Update("UPDATE data_dictionary_item SET label=#{label},description=#{description},sort_order=#{sortOrder},enabled=#{enabled},version=version+1 WHERE id=#{id} AND dictionary_code=#{dictionaryCode} AND version=#{version}")
    int updateItem(DictionaryItem input);
    @Delete("DELETE FROM data_dictionary_item WHERE id=#{id}")
    void deleteItem(long id);
    @Select("""
        SELECT (SELECT COUNT(*) FROM data_model WHERE subject_area=#{value})
          +(SELECT COUNT(*) FROM metric_definition WHERE subject_area=#{value})
          +(SELECT COUNT(*) FROM asset_profile WHERE subject_area=#{value})
          +(SELECT COUNT(*) FROM data_service WHERE subject_area=#{value})
          +(SELECT COUNT(*) FROM metric_version WHERE CASE WHEN JSON_VALID(definition_json)
            THEN JSON_UNQUOTE(JSON_EXTRACT(definition_json,'$.subjectArea')) ELSE NULL END=#{value})
        """)
    long subjectAreaReferences(String value);
}
