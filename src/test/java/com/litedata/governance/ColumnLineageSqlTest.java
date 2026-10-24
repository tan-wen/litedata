package com.litedata.governance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ColumnLineageSqlTest {
    private static final Map<String, List<String>> SCHEMA = Map.of("orders", List.of("id", "customer_id", "amount", "status"), "customers", List.of("id", "name"));
    private static ColumnLineageSql.Result parse(String sql, List<String> targets) { return ColumnLineageSql.parse(sql, targets, SCHEMA::get); }

    @Test void mapsImplicitTargetsAndSeparatesConditions() {
        var result = parse("INSERT INTO warehouse.sales SELECT o.customer_id, SUM(o.amount) total FROM warehouse.orders o JOIN customers c ON o.customer_id=c.id WHERE o.status='paid' GROUP BY o.customer_id HAVING SUM(o.amount)>10", List.of("customer", "total"));
        assertEquals("sales", result.target());
        assertEquals("customer", result.columns().getFirst().column());
        assertTrue(result.columns().getFirst().inputs().stream().anyMatch(i -> i.table().equals("orders") && i.column().equals("customer_id") && i.role().equals("表达式")));
        assertTrue(result.columns().getFirst().inputs().stream().anyMatch(i -> i.table().equals("customers") && i.column().equals("id") && i.role().equals("SQL 关联") && i.expression().contains("c.id")));
        assertTrue(result.columns().getFirst().inputs().stream().anyMatch(i -> i.table().equals("orders") && i.column().equals("status") && i.role().equals("SQL 筛选") && i.expression().contains("status")));
        assertTrue(result.columns().getFirst().inputs().stream().anyMatch(i -> i.table().equals("orders") && i.column().equals("customer_id") && i.role().equals("SQL 分组")));
        assertTrue(result.columns().getFirst().inputs().stream().anyMatch(i -> i.role().equals("SQL HAVING")));
    }
    @Test void explicitTargetsOverrideProvidedOrderingAndConstantsHaveNoInputs() {
        var result = parse("INSERT INTO target (b, a) SELECT 1, NULL", List.of("a", "b"));
        assertEquals(List.of("b", "a"), result.columns().stream().map(ColumnLineageSql.Projection::column).toList());
        assertTrue(result.columns().get(0).inputs().isEmpty());
    }
    @Test void expandsKnownWildcardsAndModelsCountStarAsRows() {
        var wildcard = parse("INSERT INTO target SELECT o.* FROM orders o", List.of("id", "customer_id", "amount", "status"));
        assertEquals(4, wildcard.columns().size());
        assertEquals("orders", wildcard.columns().get(0).inputs().getFirst().table());
        var count = parse("INSERT INTO target SELECT COUNT(*) FROM orders", List.of("n"));
        assertEquals("行数", count.columns().getFirst().inputs().getFirst().role());
        assertEquals("orders", count.columns().getFirst().inputs().getFirst().table());
    }
    @Test void astExtractionKeepsRealDateFieldsAndDoesNotReadLiteralFunctionText() {
        var schemas = Map.of("events", List.of("date"), "orders", List.of("id"));
        var date = ColumnLineageSql.parse("INSERT INTO target SELECT date FROM events", List.of("d"), schemas::get);
        assertEquals("date", date.columns().getFirst().inputs().getFirst().column());
        var literal = ColumnLineageSql.parse("INSERT INTO target SELECT 'COUNT(*)' FROM orders", List.of("label"), schemas::get);
        assertEquals(1, literal.columns().getFirst().inputs().size());
        assertEquals("SQL 行集", literal.columns().getFirst().inputs().getFirst().role());
    }
    @Test void rejectsAmbiguousUnknownAndUnsupportedForms() {
        assertThrows(IllegalArgumentException.class, () -> parse("INSERT INTO target SELECT id FROM orders o JOIN customers c ON o.customer_id=c.id", List.of("x")));
        assertThrows(IllegalArgumentException.class, () -> ColumnLineageSql.parse("INSERT INTO target SELECT * FROM orders", List.of("x"), t -> null));
        assertThrows(IllegalArgumentException.class, () -> parse("INSERT INTO target SELECT id FROM (SELECT id FROM orders) x", List.of("id")));
        assertThrows(IllegalArgumentException.class, () -> parse("INSERT INTO target SELECT id FROM orders LIMIT 1", List.of("id")));
        assertThrows(IllegalArgumentException.class, () -> parse("INSERT INTO target SELECT SUM(amount) OVER w FROM orders WINDOW w AS (PARTITION BY status)", List.of("total")));
    }
}
