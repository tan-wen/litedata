package com.litedata.data;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Resolve the same schema, column order and clock used by the DataX reader. */
public record SourceTable(String schema,List<String> columns,List<String> timestampColumns,LocalDateTime upperBound) {
    public static SourceTable inspect(Connection connection,DatabaseType type,String table,String watermark) throws SQLException {
        String schema=type.schema(connection), catalog=connection.getCatalog();
        var metadata=connection.getMetaData();
        String pattern=table.replace("_",metadata.getSearchStringEscape()+"_");
        boolean found=false;
        try(var rows=metadata.getColumns(catalog,schema,pattern,null)) {
            while(rows.next()) if(table.equals(rows.getString("TABLE_NAME")) && watermark.equals(rows.getString("COLUMN_NAME"))) {
                int jdbcType=rows.getInt("DATA_TYPE");
                String typeName=Objects.toString(rows.getString("TYPE_NAME"),"").toUpperCase(Locale.ROOT).replaceAll("\\(\\d+\\)","");
                boolean timestamp=switch(type) {
                    case MYSQL -> Set.of("DATETIME","TIMESTAMP").contains(typeName);
                    case POSTGRESQL,ORACLE -> "TIMESTAMP".equals(typeName);
                    case SQLSERVER -> Set.of("DATETIME","DATETIME2").contains(typeName);
                };
                if(jdbcType!=Types.TIMESTAMP || !timestamp || rows.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls)
                    throw new IllegalArgumentException("增量水位须为非空、不带时区的 DATETIME/TIMESTAMP（SQL Server 使用 datetime/datetime2，不能使用 rowversion）");
                found=true;
            }
        }
        if(!found) throw new IllegalArgumentException("未找到源表水位字段；请检查表名大小写及默认模式");
        boolean indexed=false;
        try(var rows=metadata.getIndexInfo(catalog,schema,table,false,false)) {
            while(rows.next()) if(rows.getShort("ORDINAL_POSITION")==1 && watermark.equals(rows.getString("COLUMN_NAME")) &&
                    (rows.getString("FILTER_CONDITION")==null || rows.getString("FILTER_CONDITION").isBlank())) indexed=true;
        }
        if(!indexed) throw new IllegalArgumentException("请为水位字段创建以该字段开头的完整索引，避免同步扫描全表");
        String qualified=(schema==null?"":type.quote(schema)+".")+type.quote(table);
        List<String> columns=new ArrayList<>();
        List<String> timestampColumns=new ArrayList<>();
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(10);
            try(var rows=statement.executeQuery("SELECT * FROM "+qualified+" WHERE 1=0")) {
                var resultMetadata=rows.getMetaData();
                if(resultMetadata.getColumnCount()>500) throw new IllegalArgumentException("源表字段数量超过500");
                for(int i=1;i<=resultMetadata.getColumnCount();i++) {
                    String name=JdbcConnections.identifier(resultMetadata.getColumnName(i));
                    columns.add(name);
                    if(resultMetadata.getColumnType(i)==Types.TIMESTAMP) timestampColumns.add(name);
                }
            }
            if(columns.isEmpty() || !columns.contains(watermark)) throw new IllegalArgumentException("源表字段结构无效");
            try(var rows=statement.executeQuery(type.getClockSql())) {
                rows.next();
                return new SourceTable(schema,List.copyOf(columns),List.copyOf(timestampColumns),rows.getObject(1,LocalDateTime.class).truncatedTo(ChronoUnit.MICROS));
            }
        }
    }
}
