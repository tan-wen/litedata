package com.litedata.transform;

import com.litedata.data.JdbcConnections;
import java.util.*;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

@Component
public class TransformSqlPolicy {
    private static final Pattern DENIED = Pattern.compile("(?i)(;|/\\*|--|#|\\b(OUTFILE|DUMPFILE|SLEEP|BENCHMARK|LOAD_FILE|GET_LOCK|RELEASE_LOCK|PROCEDURE|INFORMATION_SCHEMA|PERFORMANCE_SCHEMA|MYSQL|SYS|OVERWRITE|RETURNING|FOR|LOCK)\\b)");
    public record Validated(String sql, Set<String> sources) {}

    public Validated validate(String sql, String target) {
        target = JdbcConnections.identifier(target).toLowerCase(Locale.ROOT);
        if (sql == null || sql.isBlank() || sql.length() > 20000 || DENIED.matcher(sql).find())
            throw new IllegalArgumentException("加工 SQL 仅支持单条 INSERT INTO ... SELECT，不允许注释、多语句或副作用操作");
        try {
            var statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Insert insert) || insert.getSelect() == null || insert.getSelect() instanceof Values
                    || insert.isOverwrite() || insert.isUseDuplicate() || insert.isUseSet() || insert.isModifierIgnore()
                    || insert.getReturningClause() != null || insert.getOutputClause() != null || insert.getConflictAction() != null)
                throw new IllegalArgumentException("加工 SQL 须为 INSERT INTO ... SELECT");
            if (!table(insert.getTable().getFullyQualifiedName()).equals(target))
                throw new IllegalArgumentException("SQL 写入表必须与所选目标模型一致");
            for (String name : new TablesNamesFinder<Void>().getTables(statement)) table(name);
            var sources = new TreeSet<String>();
            for (String name : new TablesNamesFinder<Void>().getTables((net.sf.jsqlparser.statement.Statement)insert.getSelect())) sources.add(table(name));
            if (sources.isEmpty()) throw new IllegalArgumentException("加工 SELECT 须读取 warehouse 中的数据表");
            if (sources.contains(target)) throw new IllegalArgumentException("加工任务不能读取自身目标表，请使用独立的上游模型");
            return new Validated(sql.trim(), Set.copyOf(sources));
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("加工 SQL 解析失败，请检查 INSERT INTO ... SELECT 语法"); }
    }
    private static String table(String input) {
        String plain = input.replace("`", "").toLowerCase(Locale.ROOT);
        if (!plain.matches("(warehouse\\.)?[a-z_][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("加工任务仅允许访问 warehouse 库中的表");
        return plain.startsWith("warehouse.") ? plain.substring(10) : plain;
    }
}
