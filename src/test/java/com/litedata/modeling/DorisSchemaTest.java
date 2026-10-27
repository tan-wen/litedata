package com.litedata.modeling;

import static org.assertj.core.api.Assertions.*;

import com.litedata.model.DataModel;
import com.litedata.model.ModelField;
import java.util.List;
import org.junit.jupiter.api.Test;

class DorisSchemaTest {
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
