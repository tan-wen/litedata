package com.litedata.service;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface RuntimeQueryMapper {
    @SelectProvider(type=Sql.class,method="query")
    List<Map<String,Object>> query(@Param("statement") String statement,@Param("values") List<String> values,@Param("limit") int limit);
    class Sql {
        public static String query(Map<String,Object> parameters) {
            // statement has already passed QueryPolicy; only named values are MyBatis bound parameters.
            return "SELECT * FROM ("+parameters.get("statement")+") controlled_api LIMIT "+parameters.get("limit");
        }
    }
}

