package com.litedata.modeling;

import static org.assertj.core.api.Assertions.*;

import com.litedata.model.DataModel;
import com.litedata.model.ModelField;
import java.util.List;
import org.junit.jupiter.api.Test;

class DorisSchemaTest {
    @Test void comparesEquivalentRangesAndDetectsGapsAndListValueChanges() {
        var model = new DataModel(); model.setTableName("test"); model.setName("测试"); model.setSubjectArea("测试");
        var field = new ModelField(); field.setName("id"); field.setDataType("BIGINT"); field.setKeyColumn(true);
        model.setFields(List.of(field)); model.setPartitionType("MANUAL_RANGE"); model.setPartitionColumn("id"); model.setPartitionRangeStyle("LESS_THAN");
        var first = new com.litedata.model.ModelPartition(); first.setName("p_first"); first.setUpperBound("100");
        var last = new com.litedata.model.ModelPartition(); last.setName("p_last"); last.setUpperBound("MAXVALUE");
        model.setPartitions(List.of(first, last));
        String ddl = new DorisDdl().generate(model);
        var columns = List.of(new DorisModelGateway.Column("id", "BIGINT", false, ""));
        String normalized = ddl.replace("VALUES LESS THAN (\"100\")", "VALUES [(\"-9223372036854775808\"), (\"100\"))")
                .replace("VALUES LESS THAN (MAXVALUE)", "VALUES [(\"100\"), (MAXVALUE))");
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, normalized)).matches()).isTrue();
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, normalized.replace("[(\"100\")", "[(\"101\")"))).matches()).isFalse();
        field.setDataType("VARCHAR(100)"); model.setPartitionType("MANUAL_LIST");
        first.setValuesText("east,west\nPARTITION fake VALUES IN (9)"); model.setPartitions(List.of(first));
        ddl = new DorisDdl().generate(model);
        columns = List.of(new DorisModelGateway.Column("id", "VARCHAR(100)", false, ""));
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, ddl)).matches()).isTrue();
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, ddl.replace("\"east,west\"", "\"east\",\"west\""))).matches()).isFalse();
    }

    @Test void dynamicComparisonAcceptsEngineDefaultsButDetectsExplicitPolicyDrift() {
        var model = new DataModel(); model.setTableName("test"); model.setName("测试"); model.setSubjectArea("测试");
        var field = new ModelField(); field.setName("dt"); field.setDataType("DATE"); field.setKeyColumn(true);
        model.setFields(List.of(field)); model.setPartitionType("DYNAMIC"); model.setPartitionColumn("dt");
        String sql = new DorisDdl().generate(model);
        var columns = List.of(new DorisModelGateway.Column("dt", "DATE", false, ""));
        sql = sql.replace("PROPERTIES (", "PROPERTIES (\n\"dynamic_partition.start_day_of_month\" = \"1\",");
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, sql)).matches()).isTrue();
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, sql.replace("\"dynamic_partition.end\" = \"2\"", "\"dynamic_partition.end\" = \"3\""))).matches()).isFalse();
        String withAllocation = sql.replace("PROPERTIES (", "PROPERTIES (\n\"dynamic_partition.replication_allocation\" = \"tag.location.default: 1\",");
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, withAllocation)).matches()).isTrue();
        model.setDynamicPartitionProperties(model.getDynamicPartitionProperties().replace("    \"dynamic_partition.start\" = \"-1\",\n", ""));
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, withAllocation)).matches()).isFalse();
    }

    @Test void readsServerNormalizedLessThanAndQuoteAwareListValues() {
        var columns = List.of(new DorisModelGateway.Column("id", "BIGINT", false, ""), new DorisModelGateway.Column("region", "VARCHAR(50)", false, ""));
        var range = DorisModelGateway.parseSchema(columns, """
                CREATE TABLE `t` (`id` bigint NOT NULL, `region` varchar(50) NOT NULL) ENGINE=OLAP
                DUPLICATE KEY(`id`, `region`)
                PARTITION BY RANGE(`id`)
                (PARTITION p_small VALUES [("-9223372036854775808"), ("100")), PARTITION p_all VALUES [("100"), (MAXVALUE)))
                DISTRIBUTED BY HASH(`id`) BUCKETS 1 PROPERTIES ("replication_num" = "1");
                """);
        assertThat(range.partitionType()).isEqualTo("MANUAL_RANGE");
        assertThat(range.partitionRules()).extracting(DorisPartitioning.Rule::upperBound).containsExactly("100", "MAXVALUE");
        var list = DorisModelGateway.parseSchema(columns, """
                CREATE TABLE `t` (`id` bigint NOT NULL, `region` varchar(50) NOT NULL) ENGINE=OLAP
                DUPLICATE KEY(`id`, `region`)
                PARTITION BY LIST (`region`)
                (PARTITION p VALUES IN ("east","west,central","x(y)","O\\'Reilly"))
                DISTRIBUTED BY HASH(`id`) BUCKETS 1
                PROPERTIES ("replication_num" = "1");
                """);
        assertThat(list.partitionRules().getFirst().values()).containsExactly("east", "west,central", "x(y)", "O'Reilly");
    }

    @Test void readsAggregateFunctionsAutoPartitionAndReplicaAllocationFromServerDdl() {
        var schema = DorisModelGateway.parseSchema(List.of(new DorisModelGateway.Column("dt", "datev2", false, ""),
                new DorisModelGateway.Column("total", "decimalv3(18,2)", true, "")), """
                CREATE TABLE `t` (
                  `dt` datev2 NOT NULL COMMENT '',
                  `total` decimalv3(18, 2) SUM NULL COMMENT ''
                ) ENGINE=OLAP
                AGGREGATE KEY(`dt`)
                AUTO PARTITION BY RANGE (date_trunc(`dt`, 'month'))
                ()
                DISTRIBUTED BY HASH(`dt`) BUCKETS 4
                PROPERTIES (
                  "replication_allocation" = "tag.location.default: 2, tag.location.other: 1"
                );
                """);
        assertThat(schema.modelType()).isEqualTo("AGGREGATE");
        assertThat(schema.keyColumns()).containsExactly("dt");
        assertThat(schema.hashColumns()).containsExactly("dt");
        assertThat(schema.buckets()).isEqualTo(4);
        assertThat(schema.replicationNum()).isEqualTo(3);
        assertThat(schema.partitionType()).isEqualTo("AUTO_RANGE");
        assertThat(schema.partitionColumn()).isEqualTo("dt");
        assertThat(schema.partitionUnit()).isEqualTo("MONTH");
        assertThat(schema.aggregations()).containsEntry("total", "SUM");
    }

    @Test void commentsCannotMasqueradeAsPartitionOrDistributionClauses() {
        var schema = DorisModelGateway.parseSchema(List.of(), """
                CREATE TABLE `t` (`id` bigint COMMENT 'AUTO PARTITION BY RANGE and DISTRIBUTED BY HASH(`evil`) BUCKETS 99') ENGINE=OLAP
                DUPLICATE KEY(`id`)
                COMMENT 'PARTITION BY RANGE(`evil`)'
                DISTRIBUTED BY HASH(`id`) BUCKETS 2
                PROPERTIES (
                  "replication_num" = "1"
                );
                """);
        assertThat(schema.modelType()).isEqualTo("DUPLICATE");
        assertThat(schema.partitionType()).isEqualTo("NONE");
        assertThat(schema.hashColumns()).containsExactly("id");
        assertThat(schema.buckets()).isEqualTo(2);
        assertThat(schema.replicationNum()).isEqualTo(1);
    }

    @Test void distinguishesAutoPolicyFromTheActualBucketCountInServerMetadata() {
        var columns = List.of(new DorisModelGateway.Column("id", "BIGINT", false, ""));
        String sql = """
                CREATE TABLE `t` (`id` bigint NOT NULL) ENGINE=OLAP
                UNIQUE KEY(`id`)
                DISTRIBUTED BY HASH(`id`) BUCKETS AUTO
                PROPERTIES (
                "replication_allocation" = "tag.location.default: 1",
                "enable_unique_key_merge_on_write" = "true"
                );
                """;
        var model = new DataModel(); model.setTableName("t"); model.setAutoBuckets(true);
        var id = new ModelField(); id.setName("id"); id.setDataType("BIGINT"); id.setKeyColumn(true);
        model.setFields(List.of(id));
        var auto = DorisModelGateway.parseSchema(columns, sql);
        assertThat(auto.autoBuckets()).isTrue();
        assertThat(SchemaComparison.compare(model, auto).matches()).isTrue();
        String numericAuto = sql.replace("BUCKETS AUTO", "BUCKETS 10").replace("PROPERTIES (", "PROPERTIES (\n\"auto_bucket\" = \"true\",");
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, numericAuto)).matches()).isTrue();
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, sql.replace("BUCKETS AUTO", "BUCKETS 10"))).matches()).isFalse();
        model.setAutoBuckets(false); model.setBuckets(10);
        assertThat(SchemaComparison.compare(model, auto).matches()).isFalse();
    }

    @Test void comparesCustomHashAndRandomDistributionIncludingAutoPolicy() {
        var model = new DataModel(); model.setTableName("test"); model.setName("测试"); model.setSubjectArea("测试");
        model.setModelType("DUPLICATE"); model.setBuckets(8);
        var id = new ModelField(); id.setName("id"); id.setDataType("BIGINT"); id.setKeyColumn(true);
        var region = new ModelField(); region.setName("region"); region.setDataType("VARCHAR(20)"); region.setNullable(true);
        model.setFields(List.of(id, region)); model.setDistributionColumns("region,id");
        var columns = List.of(new DorisModelGateway.Column("id", "BIGINT", false, ""), new DorisModelGateway.Column("region", "VARCHAR(20)", true, ""));
        String hash = new DorisDdl().generate(model);
        var schema = DorisModelGateway.parseSchema(columns, hash);
        assertThat(schema.distributionType()).isEqualTo("HASH");
        assertThat(schema.hashColumns()).containsExactly("region", "id");
        assertThat(SchemaComparison.compare(model, schema).matches()).isTrue();
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, hash.replace("HASH(`region`, `id`)", "HASH(`id`)"))).matches()).isFalse();
        model.setDistributionType("RANDOM");
        String random = new DorisDdl().generate(model);
        schema = DorisModelGateway.parseSchema(columns, random);
        assertThat(schema.distributionType()).isEqualTo("RANDOM");
        assertThat(schema.hashColumns()).isEmpty();
        assertThat(schema.buckets()).isEqualTo(8);
        assertThat(SchemaComparison.compare(model, schema).matches()).isTrue();
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, hash)).matches()).isFalse();
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, random.replace("BUCKETS 8", "BUCKETS 9"))).matches()).isFalse();
        model.setAutoBuckets(true);
        random = new DorisDdl().generate(model);
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, random)).matches()).isTrue();
        String numericAuto = random.replace("BUCKETS AUTO", "BUCKETS 10").replace("PROPERTIES (", "PROPERTIES (\n\"auto_bucket\" = \"true\",");
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, numericAuto)).matches()).isTrue();
    }

    @Test void comparisonDetectsPhysicalSettingsAndAggregationDrift() {
        var model = new DataModel(); model.setTableName("dws_test"); model.setName("测试"); model.setSubjectArea("测试");
        model.setModelType("AGGREGATE"); model.setPartitionType("AUTO_RANGE"); model.setPartitionColumn("dt");
        var date = new ModelField(); date.setName("dt"); date.setDataType("DATE"); date.setKeyColumn(true);
        var total = new ModelField(); total.setName("total"); total.setDataType("INT"); total.setAggregation("SUM");
        model.setFields(List.of(date, total));
        var sql = new DorisDdl().generate(model);
        var columns = List.of(new DorisModelGateway.Column("dt", "DATE", false, ""), new DorisModelGateway.Column("total", "INT", false, ""));
        assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, sql)).matches()).isTrue();
        for (String changed : List.of(sql.replace("INT SUM", "INT MAX"), sql.replace("AGGREGATE KEY", "DUPLICATE KEY"),
                sql.replace("'day'", "'month'"), sql.replace("\"replication_num\" = \"1\"", "\"replication_num\" = \"2\""))) {
            assertThat(SchemaComparison.compare(model, DorisModelGateway.parseSchema(columns, changed)).matches()).isFalse();
        }
    }
}
