package com.litedata.modeling;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.litedata.service.QueryPolicy;
import com.litedata.store.ModelDependencyMapper;
import com.litedata.store.ModelDependencyMapper.SqlReference;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelDependencyGuardTest {
    private final ModelDependencyMapper mapper = mock(ModelDependencyMapper.class);
    private final ModelDependencyGuard guard = new ModelDependencyGuard(mapper, new QueryPolicy());

    @Test
    void recognizesSqlInputsAndNamedParameterServiceQueriesWithoutSubstringFalsePositives() {
        when(mapper.tasks("ods_orders")).thenReturn(List.of(new SqlReference("writer", "INSERT INTO ads_orders SELECT id FROM `warehouse`.`ODS_ORDERS`")));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.check("ods_orders")).withMessageContaining("数据加工");
        when(mapper.tasks("ods_orders")).thenReturn(List.of(new SqlReference("other", "INSERT INTO ads_orders SELECT id FROM ods_orders_backup")));
        when(mapper.services("ods_orders")).thenReturn(List.of(new SqlReference("api", "SELECT id FROM ods_orders WHERE id=:customerId")));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.check("ods_orders")).withMessageContaining("数据服务");
        when(mapper.services("ods_orders")).thenReturn(List.of());
        assertThatCode(() -> guard.check("ods_orders")).doesNotThrowAnyException();
    }

    @Test
    void protectsQualityTablesAndCustomSqlAndFailsClosedOnUnparseableDependencies() {
        when(mapper.qualityReferences("ods_orders")).thenReturn(1L);
        assertThatIllegalArgumentException().isThrownBy(() -> guard.check("ods_orders")).withMessageContaining("数据质量");
        when(mapper.qualityReferences("ods_orders")).thenReturn(0L);
        when(mapper.qualityQueries("ods_orders")).thenReturn(List.of(new SqlReference("scan", "SELECT id FROM warehouse.ods_orders")));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.check("ods_orders")).withMessageContaining("质量 SQL");
        when(mapper.tasks("ods_orders")).thenReturn(List.of(new SqlReference("invalid", "broken SQL ods_orders")));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.check("ods_orders")).withMessageContaining("无法校验");
    }
}
