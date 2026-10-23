package com.litedata.modeling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.litedata.model.DataModel;
import com.litedata.model.ModelField;
import java.util.List;
import org.junit.jupiter.api.Test;

class DorisDdlTest {
    private final DorisDdl ddl = new DorisDdl();

    @Test void generatesFixedWarehouseUniqueKeyDdlAndNormalizesModel() {
        var model = model(field("Order_ID", "bigint(20)", true, false, "业务 '主键'"),
                field("created_at", "datetimev2(6)", false, false, "", "ISO-8601"),
                field("amount", "decimalv3(18,2)", false, true, "金额", ""));
        model.setTableName("ODS_Orders"); model.setName("订单明细"); model.setDescription("日增量"); model.setBuckets(4);

        String sql = ddl.generate(model);

        assertThat(sql).contains("CREATE TABLE `warehouse`.`ods_orders`", "`order_id` BIGINT NOT NULL COMMENT '业务 \\'主键\\''",
                "`created_at` DATETIME(6) NOT NULL", "UNIQUE KEY(`order_id`)",
                "DISTRIBUTED BY HASH(`order_id`) BUCKETS 4", "\"replication_num\" = \"1\"",
                "\"enable_unique_key_merge_on_write\" = \"true\"", "COMMENT '订单明细 - 日增量'");
        assertThat(sql.indexOf("ENGINE=OLAP")).isLessThan(sql.indexOf("UNIQUE KEY(`order_id`)"));
        assertThat(sql.indexOf("UNIQUE KEY(`order_id`)")).isLessThan(sql.indexOf("COMMENT '订单"));
        assertThat(sql.indexOf("COMMENT '订单")).isLessThan(sql.indexOf("DISTRIBUTED BY HASH"));
        assertThat(model.getFields().getFirst().getDataType()).isEqualTo("BIGINT");
        assertThat(model.getTableName()).isEqualTo("ods_orders");
    }

    @Test void rejectsSqlInjectionAndUnsafeComments() {
        var injected = model(field("id", "BIGINT); DROP TABLE x; --", true, false));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(injected));
        var unsafeComment = model(field("id", "BIGINT", true, false, "line\nbreak"));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(unsafeComment));
        var backslashComment = model(field("id", "BIGINT", true, false, "path\\identifier"));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(backslashComment));
        var unsafeName = model(field("id;drop", "BIGINT", true, false));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(unsafeName));
    }

    @Test void enforcesKeysTypesAndBoundaries() {
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model(field("note", "STRING", false, true))));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model(field("ratio", "FLOAT", true, false))));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model(field("id", "BIGINT", true, true))));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model(field("value", "VARCHAR(10)", false, true), field("id", "BIGINT", true, false))));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model(field("id", "VARCHAR(65534)", true, false))));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model(field("id", "VARCHAR(999999999999999999999)", true, false))));
        var valid = model(field("id", "CHAR(255)", true, false), field("label", "VARCHAR(65533)", false, true));
        valid.setBuckets(32);
        assertThat(ddl.generate(valid)).contains("BUCKETS 32");
    }

    @Test void canonicalizesDorisAliasesForComparison() {
        assertThat(DorisDdl.canonicalType(" datev2 ")).isEqualTo("DATE");
        assertThat(DorisDdl.canonicalType("datetimev2(3)")).isEqualTo("DATETIME(3)");
        assertThat(DorisDdl.canonicalType("datetimev2(0)")).isEqualTo("DATETIME");
        assertThat(DorisDdl.canonicalType("decimalv3(18, 2)")).isEqualTo("DECIMAL(18,2)");
        assertThat(DorisDdl.canonicalType("BOOLEAN")).isEqualTo("TINYINT");
        assertThat(DorisDdl.canonicalType("bigint(20)")).isEqualTo("BIGINT");
    }

    private static DataModel model(ModelField... fields) {
        var model = new DataModel();
        model.setTableName("ods_orders"); model.setName("订单"); model.setSubjectArea("交易"); model.setLayer("ods");
        model.setDescription(""); model.setBuckets(1); model.setFields(List.of(fields));
        return model;
    }
    private static ModelField field(String name, String type, boolean key, boolean nullable) { return field(name, type, key, nullable, ""); }
    private static ModelField field(String name, String type, boolean key, boolean nullable, String comment) { return field(name, type, key, nullable, comment, ""); }
    private static ModelField field(String name, String type, boolean key, boolean nullable, String comment, String standard) {
        var field = new ModelField(); field.setName(name); field.setDataType(type); field.setKeyColumn(key); field.setNullable(nullable);
        field.setComment(comment); field.setStandard(standard); return field;
    }
}
