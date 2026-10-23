package com.litedata.service;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface RuntimeQueryMapper {
    @SelectProvider(type=Sql.class,method="query")
    List<Map<String,Object>> query(@Param("statement") String statement,@Param("values") List<String> values,@Param("limit") int limit,@Param("offset") long offset);
    class Sql {
        public static String query(Map<String,Object> parameters) {
            // statement has passed QueryPolicy; business values and pagination are bound parameters.
            return "SELECT * FROM ("+parameters.get("statement")+") controlled_api LIMIT #{limit} OFFSET #{offset}";
        }
    }
}

