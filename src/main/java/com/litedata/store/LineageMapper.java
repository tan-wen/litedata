package com.litedata.store;

import com.litedata.model.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LineageMapper {
    @Select("SELECT * FROM pipeline ORDER BY id LIMIT 5001") List<Pipeline> pipelines();
    @Select(SqlTaskMapper.TASK_COLUMNS + "WHERE t.archived=FALSE ORDER BY t.id LIMIT 5001") List<SqlTask> tasks();
}
