package com.litedata.metric;

import static org.assertj.core.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricExpressionsTest {
    @Test void compilesArithmeticWithPrecedenceAndGuardsEveryDenominator() {
        assertThat(MetricExpressions.formula("[amount] / ([orders] - 1) * 100", code -> "m." + code))
                .isEqualTo("((m.amount / NULLIF(((m.orders - 1)), 0)) * 100)");
        assertThat(MetricExpressions.references("[a] + [a] / [b]")).containsExactly("a", "b");
        for (String input : new String[]{"SUM([a])", "[a]; SELECT 1", "[a] +", "[a] [b]", "'a'", "1"})
            assertThatIllegalArgumentException().isThrownBy(() -> MetricExpressions.references(input));
    }
    @Test void onlyAllowsKnownColumnsAndLiteralConditions() {
        var fields = Set.of("amount", "status", "region");
        assertThat(MetricExpressions.filter("amount >= 0 AND (status = 'paid' OR region IS NULL)", fields))
                .contains("`amount` >= 0", "`status` = 'paid'", "`region` IS NULL");
        assertThat(MetricExpressions.filter("status IN ('it''s paid', 'pending')", fields)).contains("'it''s paid'");
        assertThat(MetricExpressions.filterColumns("status = 'amount' AND (amount > 0 OR region IS NULL)"))
                .containsExactly("status", "amount", "region");
        for (String input : new String[]{"amount > 0; DELETE FROM orders", "amount = SLEEP(1)", "unknown = 1", "amount > (SELECT 1)", "amount > 0 -- comment", "status = 'bad\\' OR 1=1", "amount = amount"})
            assertThatIllegalArgumentException().isThrownBy(() -> MetricExpressions.filter(input, fields));
        assertThatIllegalArgumentException().isThrownBy(() -> MetricExpressions.filter("(".repeat(40) + "amount = 1" + ")".repeat(40), fields));
    }
}
