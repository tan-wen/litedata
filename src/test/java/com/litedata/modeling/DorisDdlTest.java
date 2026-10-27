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

    @Test void generatesDuplicateAndAggregateModelsWithConfigurableReplicas() {
        var duplicate = model(field("id", "BIGINT", true, false), field("label", "VARCHAR(20)", false, true));
        duplicate.setModelType(" duplicate "); duplicate.setReplicationNum(3);
        assertThat(ddl.generate(duplicate)).contains("DUPLICATE KEY(`id`)", "\"replication_num\" = \"3\"")
                .doesNotContain("enable_unique_key_merge_on_write");
        var aggregate = model(field("day", "DATE", true, false),
                aggregate("amount", "DECIMAL(18,2)", "SUM", false),
                aggregate("low", "VARCHAR(20)", "MIN", true), aggregate("high", "BIGINT", "MAX", false),
                aggregate("name", "STRING", "REPLACE", true), aggregate("status", "VARCHAR(20)", "REPLACE_IF_NOT_NULL", true));
        aggregate.setModelType("AGGREGATE");
        assertThat(ddl.generate(aggregate)).contains("AGGREGATE KEY(`day`)", "DECIMAL(18,2) SUM NOT NULL",
                "VARCHAR(20) MIN NULL", "BIGINT MAX NOT NULL", "STRING REPLACE NULL", "VARCHAR(20) REPLACE_IF_NOT_NULL NULL")
                .doesNotContain("enable_unique_key_merge_on_write");
    }

    @Test void autoBucketingSupportsAllModelsWithoutChangingLegacyFixedBuckets() {
        for (String kind : List.of("UNIQUE", "DUPLICATE", "AGGREGATE")) {
            var model = model(field("dt", "DATE", true, false), field("total", "INT", false, false));
            model.setModelType(kind); model.setAutoBuckets(true);
            if (kind.equals("AGGREGATE")) model.getFields().getLast().setAggregation("SUM");
            assertThat(ddl.generate(model)).contains("BUCKETS AUTO");
            model.setPartitionType("AUTO_RANGE"); model.setPartitionColumn("dt");
            assertThat(ddl.generate(model)).contains("AUTO PARTITION BY RANGE", "BUCKETS AUTO");
            model.setAutoBuckets(false); model.setBuckets(4);
            assertThat(ddl.generate(model)).contains("BUCKETS 4").doesNotContain("BUCKETS AUTO");
        }
    }

    @Test void generatesStructuredDatePartitionsAndRejectsInvalidDefinitions() {
        for (String unit : List.of("DAY", "MONTH", "YEAR")) {
            var model = model(field("biz_date", "DATE", true, false), field("id", "BIGINT", true, false));
            model.setPartitionType("AUTO_RANGE"); model.setPartitionColumn(" BIZ_DATE "); model.setPartitionUnit(unit);
            assertThat(ddl.generate(model)).contains("AUTO PARTITION BY RANGE(date_trunc(`biz_date`, '" + unit.toLowerCase() + "')) ()");
            assertThat(model.getPartitionColumn()).isEqualTo("biz_date");
        }
        for (String column : List.of("id", "missing", "biz_date); DROP TABLE x")) {
            var model = model(field("id", "BIGINT", true, false), field("biz_date", "DATE", false, false));
            model.setPartitionType("AUTO_RANGE"); model.setPartitionColumn(column);
            assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model));
        }
        var model = model(field("biz_date", "DATE", true, false));
        model.setPartitionType("AUTO_RANGE"); model.setPartitionColumn("biz_date"); model.setPartitionUnit("day'); DROP");
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model));
        model.setPartitionType("LIST");
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model));
        model.setPartitionType("NONE");
        assertThat(ddl.generate(model)).doesNotContain("PARTITION BY");
        assertThat(model.getPartitionColumn()).isEmpty();
    }

    @Test void validatesModelsReplicasAggregationsAndDimensionSemantics() {
        for (int replicas : List.of(0, 11)) {
            var model = model(field("id", "BIGINT", true, false)); model.setReplicationNum(replicas);
            assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model));
        }
        var unknown = model(field("id", "BIGINT", true, false)); unknown.setModelType("UNIQUE); DROP");
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(unknown));
        for (ModelField value : List.of(field("v", "INT", false, false), aggregate("v", "STRING", "SUM", true),
                aggregate("v", "INT", "REPLACE_IF_NOT_NULL", false), aggregate("v", "INT", "COUNT", true))) {
            var model = model(field("id", "BIGINT", true, false), value); model.setModelType("AGGREGATE");
            assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model));
        }
        var key = aggregate("id", "BIGINT", "SUM", false); key.setKeyColumn(true);
        var invalidKey = model(key); invalidKey.setModelType("AGGREGATE");
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(invalidKey));
        var ordinary = model(field("id", "BIGINT", true, false), aggregate("v", "INT", "SUM", true));
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(ordinary));
        var dimension = dimension(); dimension.setModelType("DUPLICATE");
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(dimension)).withMessageContaining("DIM/SCD1");
        dimension.setModelType("UNIQUE"); dimension.setPartitionType("AUTO_RANGE");
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(dimension)).withMessageContaining("DIM/SCD1");
    }

    private static ModelField aggregate(String name, String type, String function, boolean nullable) {
        var field = field(name, type, false, nullable); field.setAggregation(function); return field;
    }

    @Test void dimensionBusinessKeysEnforceCurrentMemberUniquenessAndNormalizeMetadata() {
        var model = dimension();
        model.setDimensionKeys(" TENANT_ID , enterprise_id "); model.setDimensionLabel(" Enterprise_Name ");
        assertThat(ddl.generate(model)).contains("UNIQUE KEY(`tenant_id`, `enterprise_id`)");
        assertThat(model.getDimensionKeys()).isEqualTo("tenant_id,enterprise_id");
        assertThat(model.getDimensionLabel()).isEqualTo("enterprise_name");
        assertThat(model.getDimensionStrategy()).isEqualTo("SCD1");
        assertThat(model.dimensionRole(model.getFields().getFirst())).isEqualTo("业务键");
        assertThat(model.dimensionRole(model.getFields().getLast())).isEqualTo("展示名称");
        model.setDimensionLabel(""); ddl.generate(model);
        assertThat(model.dimensionRole(model.getFields().getLast())).isEqualTo("维度属性");
        model.setLayer("DWD"); ddl.generate(model);
        assertThat(model.getDimensionKeys()).isEmpty();
        assertThat(model.getDimensionLabel()).isEmpty();
        assertThat(model.getDimensionStrategy()).isEmpty();
    }

    @Test void rejectsInvalidDimensionKeysLabelsAndUnsupportedHistoryStrategy() {
        for (String keys : List.of("", "tenant_id", "enterprise_id,tenant_id", "tenant_id,tenant_id", "missing", "tenant_id,enterprise_id,", "tenant_id;drop")) {
            var model = dimension(); model.setDimensionKeys(keys);
            assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model));
        }
        for (String label : List.of("missing", "tenant_id", "name;drop")) {
            var model = dimension(); model.setDimensionLabel(label);
            assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model));
        }
        var model = dimension(); model.setDimensionStrategy("SCD2");
        assertThatIllegalArgumentException().isThrownBy(() -> ddl.generate(model)).withMessageContaining("仅支持 SCD1");
    }

    private static DataModel dimension() {
        var model = model(field("tenant_id", "BIGINT", true, false), field("enterprise_id", "BIGINT", true, false),
                field("enterprise_name", "VARCHAR(128)", false, false));
        model.setLayer("dim"); model.setDimensionKeys("tenant_id,enterprise_id");
        return model;
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
