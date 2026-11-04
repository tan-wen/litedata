package com.litedata.modeling;

import com.litedata.service.QueryPolicy;
import com.litedata.store.ModelDependencyMapper;
import java.util.Locale;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/** Checks current downstream configurations; historical snapshots do not reserve a live model. */
@Component
public class ModelDependencyGuard {
    private final ModelDependencyMapper mapper;
    private final QueryPolicy queries;
    public ModelDependencyGuard(ModelDependencyMapper mapper, QueryPolicy queries) {
        this.mapper=mapper; this.queries=queries;
    }

    public void check(String table) {
        for (var task : mapper.tasks(table))
            if (references(task.sqlText(),table)) throw new IllegalArgumentException("模型仍被数据加工读取或写入，请先解除依赖："+task.name());
        for (var service : mapper.services(table))
            if (references(queries.validate(service.sqlText()).sql(),table)) throw new IllegalArgumentException("模型仍被数据服务引用，请先解除依赖："+service.name());
        if (mapper.qualityReferences(table)>0) throw new IllegalArgumentException("模型仍被数据质量任务引用，请先解除依赖");
        for (var rule : mapper.qualityQueries(table))
            if (references(queries.validate(rule.sqlText()).sql(),table)) throw new IllegalArgumentException("模型仍被质量 SQL 引用，请先解除依赖："+rule.name());
    }

    private boolean references(String sql,String table) {
        try {
            return new TablesNamesFinder<Void>().getTables(CCJSqlParserUtil.parse(sql)).stream()
                    .map(name -> name.replace("`", "").toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.equals(table.toLowerCase(Locale.ROOT)) || name.equals("warehouse."+table.toLowerCase(Locale.ROOT)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法校验相关 SQL 的模型依赖，请先修正配置后再删除");
        }
    }
}
