package com.litedata.pipeline;

import com.litedata.data.SourceSchemaInspector;
import com.litedata.model.DataModel;
import com.litedata.model.DataSource;
import com.litedata.modeling.DorisDdl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class OdsSchemaPolicy {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private OdsSchemaPolicy() {}
    public static String sourceHash(DataSource source,String table,SourceSchemaInspector.Table schema) {
        var columns=schema.columns().stream().map(c->List.of(c.name(),c.jdbcType(),c.typeName(),c.precision(),c.scale(),c.nullable(),c.signed())).toList();
        return hash(JSON.writeValueAsString(List.of(source.getId(),source.getJdbcUrl(),source.getDatabaseType().name(),
                Objects.toString(schema.schema(),""),table,columns,schema.primaryKeys())));
    }
    public static String targetHash(DataModel model) {
        return hash(JSON.writeValueAsString(List.of(model.getModelType(),model.getFields().stream()
                .map(f->List.of(f.getName(),DorisDdl.canonicalType(f.getDataType()),f.isNullable(),f.isKeyColumn())).toList())));
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static String sourceType(SourceSchemaInspector.Column column) {
        String type=column.typeName();
        if(type.contains("(")) return type;
        return switch(column.jdbcType()) {
            case java.sql.Types.DECIMAL,java.sql.Types.NUMERIC -> type+"("+column.precision()+","+column.scale()+")";
            case java.sql.Types.TIMESTAMP,java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> type+"("+column.scale()+")";
            case java.sql.Types.CHAR,java.sql.Types.VARCHAR,java.sql.Types.NCHAR,java.sql.Types.NVARCHAR -> type+"("+column.precision()+")";
            default -> type;
        };
    }
}
