package com.litedata.transform;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TransformSqlPolicyTest {
    private final TransformSqlPolicy policy=new TransformSqlPolicy();
    @Test void acceptsJoinAggregationAndWarehouseQualifiedNames() {
        var result=policy.validate("INSERT INTO warehouse.dws_orders SELECT customer_id, SUM(amount) FROM warehouse.dwd_orders GROUP BY customer_id","dws_orders");
        assertThat(result.sources()).containsExactly("dwd_orders");
        assertThat(policy.validate("INSERT INTO ads_orders SELECT a.id FROM dwd_orders a JOIN dws_customers b ON a.id=b.id","ads_orders").sources())
                .containsExactlyInAnyOrder("dwd_orders","dws_customers");
    }
    @Test void rejectsWrongTargetOtherDatabasesMultiStatementsAndSideEffects() {
        for(String sql:new String[]{"INSERT INTO other SELECT id FROM ods_orders", "INSERT INTO dwd_orders VALUES(1)",
                "INSERT INTO dwd_orders SELECT id FROM mysql.user", "INSERT INTO dwd_orders SELECT id FROM ods_orders; DELETE FROM x",
                "INSERT INTO dwd_orders SELECT SLEEP(10) FROM ods_orders", "INSERT INTO dwd_orders SELECT id FROM dwd_orders",
                "DELETE FROM dwd_orders", "INSERT OVERWRITE dwd_orders SELECT id FROM ods_orders", "INSERT INTO dwd_orders SELECT id FROM ods_orders --comment"})
            assertThatIllegalArgumentException().as(sql).isThrownBy(()->policy.validate(sql,"dwd_orders"));
    }
    @Test void acceptsCteSourceWithoutTreatingCteAliasAsAnExternalTable() {
        var result=policy.validate("INSERT INTO dwd_orders WITH selected AS (SELECT id FROM ods_orders) SELECT id FROM selected","dwd_orders");
        assertThat(result.sources()).contains("ods_orders").doesNotContain("selected");
    }
}
