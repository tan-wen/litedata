package com.litedata.store;

import com.litedata.model.ServiceCall;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ServiceCallMapper {
    @Insert("INSERT INTO service_call(service_id,started_at,duration_ms,status_code,row_count,page_num,page_size) VALUES(#{serviceId},#{startedAt},#{durationMs},#{statusCode},#{rowCount},#{pageNum},#{pageSize})")
    void insert(ServiceCall call);
    @Select("SELECT * FROM service_call WHERE service_id=#{serviceId} ORDER BY started_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ServiceCall> calls(@Param("serviceId") long serviceId,@Param("limit") int limit,@Param("offset") long offset);
}
