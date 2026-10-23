package com.litedata.service;

import java.util.*;
import java.util.regex.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

@Component
public class QueryPolicy {
    private static final Pattern DENIED=Pattern.compile("(?i)(;|/\\*|--|#|\\b(INTO|OUTFILE|DUMPFILE|FOR|LOCK|SLEEP|BENCHMARK|LOAD_FILE|GET_LOCK|RELEASE_LOCK|PROCEDURE|INFORMATION_SCHEMA|PERFORMANCE_SCHEMA|MYSQL|SYS)\\b)");
    public BoundQuery validate(String sql) {
        if(sql==null || sql.isBlank() || sql.length()>20000 || DENIED.matcher(sql).find())
            throw new IllegalArgumentException("查询仅支持受控 SELECT，不允许注释、多语句、锁或副作用函数");
        try {
            // Named values are replaced only after token discovery; values never become SQL text.
            List<String> params=new ArrayList<>();
            StringBuilder jdbc=new StringBuilder(), mapped=new StringBuilder();
            char quote=0;
            for(int i=0;i<sql.length();i++) {
                char c=sql.charAt(i);
                if(quote!=0) {
                    jdbc.append(c); mapped.append(c);
                    if(c==92 && i+1<sql.length()) { char next=sql.charAt(++i); jdbc.append(next); mapped.append(next); }
                    else if(c==quote) {
                        if(i+1<sql.length() && sql.charAt(i+1)==quote) { jdbc.append(quote); mapped.append(quote); i++; }
                        else quote=0;
                    }
                } else if(c==39 || c==34 || c==96) { quote=c; jdbc.append(c); mapped.append(c); }
                else if(c==':' && i+1<sql.length() && Character.isLetter(sql.charAt(i+1))) {
                    int end=i+2;
                    while(end<sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end)=='_')) end++;
                    String name=sql.substring(i+1,end);
                    if(!name.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("命名参数仅支持英文字母、数字和下划线");
                    if(name.equals("pageNum") || name.equals("pageSize")) throw new IllegalArgumentException("pageNum 和 pageSize 为固定分页参数，不能用作 SQL 业务参数");
                    mapped.append("#{values[").append(params.size()).append("]}"); jdbc.append('?'); params.add(name); i=end-1;
                } else { jdbc.append(c); mapped.append(c); }
            }
            var statement=CCJSqlParserUtil.parse(jdbc.toString());
            if(!(statement instanceof Select)) throw new IllegalArgumentException("仅支持 SELECT");
            Set<String> tables=new TablesNamesFinder<Void>().getTables(statement);
            if(tables.isEmpty()) throw new IllegalArgumentException("查询须引用 warehouse 中的数据表");
            for(String table:tables) {
                String plain=table.replace("`","");
                if(!plain.matches("(?i)(warehouse\\.)?[A-Za-z_][A-Za-z0-9_]{0,63}"))
                    throw new IllegalArgumentException("查询仅允许访问 warehouse 库");
            }
            return new BoundQuery(jdbc.toString(),mapped.toString(),List.copyOf(params));
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("查询 SQL 解析失败，请检查语法和命名参数"); }
    }
    public record BoundQuery(String sql,String mappedSql,List<String> parameters) {}
}

