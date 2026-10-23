package com.litedata.governance;

import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** Describes table reads/writes only; it does not execute SQL or infer scheduling dependencies. */
public final class LineageSql {
    private LineageSql() {}
    public record Tables(String target,Set<String> sources) {}
    public static Tables parse(String sql) {
        if(sql==null || sql.isBlank() || sql.length()>20000 || sql.contains(";"))
            throw new IllegalArgumentException("无法解析表级读写关系");
        try {
            var statement=CCJSqlParserUtil.parse(sql);
            if(!(statement instanceof Insert insert) || insert.getSelect()==null || insert.getSelect() instanceof Values)
                throw new IllegalArgumentException();
            String target=table(insert.getTable().getFullyQualifiedName());
            var sources=new TreeSet<String>();
            for(String name:new TablesNamesFinder<Void>().getTables((Statement)insert.getSelect())) sources.add(table(name));
            if(sources.isEmpty()) throw new IllegalArgumentException();
            return new Tables(target,Collections.unmodifiableSet(sources));
        } catch(Exception exception) { throw new IllegalArgumentException("无法解析表级读写关系"); }
    }
    private static String table(String value) {
        String plain=value.replace("`", "").toLowerCase(Locale.ROOT);
        if(!plain.matches("(warehouse\\.)?[a-z_][a-z0-9_]{0,63}")) throw new IllegalArgumentException();
        return plain.startsWith("warehouse.")?plain.substring(10):plain;
    }
}
