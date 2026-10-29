package com.litedata.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import com.litedata.data.DatabaseType;
import com.litedata.data.SourceSchemaInspector.Column;
import java.sql.Types;
import org.junit.jupiter.api.Test;

class OdsTypeMappingTest {
    @Test void mapsSupportedTypesAcrossRegisteredSources() {
        assertEquals("INT", convert(DatabaseType.MYSQL, Types.INTEGER, "MEDIUMINT", 9, 0, true));
        assertEquals("INT", convert(DatabaseType.POSTGRESQL, Types.INTEGER, "int4", 10, 0, true));
        assertEquals("DATETIME(6)", convert(DatabaseType.SQLSERVER, Types.TIMESTAMP, "datetime2", 27, 6, true));
        assertEquals("DATETIME", convert(DatabaseType.ORACLE, Types.DATE, "DATE", 7, 0, true));
    }

    @Test void widensUnsignedAndRejectsUnsignedBigint() {
        assertEquals("SMALLINT", convert(DatabaseType.MYSQL, Types.TINYINT, "TINYINT UNSIGNED", 3, 0, false));
        assertEquals("BIGINT", convert(DatabaseType.MYSQL, Types.INTEGER, "INT UNSIGNED", 10, 0, false));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.MYSQL, Types.BIGINT, "BIGINT UNSIGNED", 20, 0, false));
    }

    @Test void reservesUtf8BytesAndChecksDecimalBounds() {
        assertEquals("VARCHAR(400)", convert(DatabaseType.POSTGRESQL, Types.VARCHAR, "varchar", 100, 0, true));
        assertEquals("STRING", convert(DatabaseType.SQLSERVER, Types.NVARCHAR, "nvarchar", 20000, 0, true));
        assertEquals("DECIMAL(38,38)", convert(DatabaseType.ORACLE, Types.NUMERIC, "NUMBER", 38, 38, true));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.MYSQL, Types.DECIMAL, "DECIMAL", 39, 2, true));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.MYSQL, Types.DECIMAL, "DECIMAL", 10, 11, true));
    }

    @Test void choosesFloatingPrecisionAndRejectsTimezoneAndWideBits() {
        assertEquals("DOUBLE", convert(DatabaseType.POSTGRESQL, Types.FLOAT, "float4", 24, 0, true));
        assertEquals("FLOAT", convert(DatabaseType.POSTGRESQL, Types.REAL, "float4", 24, 0, true));
        assertEquals("DOUBLE", convert(DatabaseType.POSTGRESQL, Types.FLOAT, "float8", 53, 0, true));
        assertEquals("TINYINT", convert(DatabaseType.SQLSERVER, Types.BIT, "bit", 1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.POSTGRESQL, Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz", 35, 6, true));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.MYSQL, Types.BIT, "BIT", 8, 0, false));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.MYSQL, Types.TIMESTAMP, "TIMESTAMP", 26, 7, true));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.ORACLE, Types.TIMESTAMP, "TIMESTAMP WITH LOCAL TIME ZONE", 35, 6, true));
        assertThrows(IllegalArgumentException.class, () -> convert(DatabaseType.ORACLE, Types.FLOAT, "FLOAT", 126, 0, true));
    }

    private static String convert(DatabaseType source, int jdbc, String type, int precision, int scale, boolean signed) {
        return OdsTypeMapping.convert(source, new Column("field", jdbc, type, precision, scale, true, signed, ""));
    }
}
