package com.litedata.governance;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LineageSqlTest {
    @Test void extractsJoinSubqueryUnionAndCteWithoutAliases() {
        var parsed=LineageSql.parse("INSERT INTO `warehouse`.`ads_orders` WITH chosen AS (SELECT id FROM warehouse.ods_orders) SELECT a.id FROM chosen a JOIN (SELECT id FROM dwd_customers) b ON a.id=b.id UNION ALL SELECT id FROM ods_archive");
        assertThat(parsed.target()).isEqualTo("ads_orders");
        assertThat(parsed.sources()).containsExactly("dwd_customers","ods_archive","ods_orders");
    }
    @Test void rejectsUnsupportedSqlInsteadOfInventingRelationships() {
        for(String sql:new String[]{"DELETE FROM ods_orders","INSERT INTO ads_orders VALUES(1)","INSERT INTO ads_orders SELECT 1",
                "INSERT INTO ads_orders SELECT id FROM other.orders","INSERT INTO ads_orders SELECT id FROM ods_orders; DELETE FROM ods_orders","broken sql"})
            assertThatIllegalArgumentException().as(sql).isThrownBy(()->LineageSql.parse(sql));
    }
}
