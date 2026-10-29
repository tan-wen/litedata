package com.litedata.pipeline;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.litedata.config.PlatformProperties;
import com.litedata.data.SourceService;
import com.litedata.model.DataModel;
import com.litedata.model.Pipeline;
import com.litedata.store.ModelMapper;
import com.litedata.store.PlatformMapper;
import org.junit.jupiter.api.Test;

class PipelineModelSafetyTest {
    private final PlatformMapper platform = mock(PlatformMapper.class);
    private final ModelMapper models = mock(ModelMapper.class);
    private final PipelineService service = new PipelineService(platform, mock(SourceService.class), mock(PlatformProperties.class), models,mock(OdsSchemaGuard.class));

    @Test void onlyRegisteredOdsUniqueTargetsCanReceiveSyncAndInlineSqlIsRejected() {
        var duplicate = new DataModel(); duplicate.setLayer("ODS"); duplicate.setModelType("DUPLICATE");
        when(models.modelByTable("ods_events")).thenReturn(duplicate);
        assertThatIllegalArgumentException().isThrownBy(() -> service.create("test", 1, "orders", "updated_at", "ods_events", "", "0 0 * * * ?"))
                .withMessageContaining("UNIQUE");
        var ods = new DataModel(); ods.setLayer("ODS"); ods.setModelType("UNIQUE");
        when(models.modelByTable("ods_ok")).thenReturn(ods);
        assertThatIllegalArgumentException().isThrownBy(() -> service.create("test", 1, "orders", "updated_at", "ods_ok",
                "INSERT INTO warehouse.ads_totals SELECT id FROM warehouse.ods_ok", "0 0 * * * ?"))
                .withMessageContaining("数据加工");
        var ads = new DataModel(); ads.setLayer("ADS"); ads.setModelType("UNIQUE");
        when(models.modelByTable("ads_totals")).thenReturn(ads);
        for(String target:java.util.List.of("ads_totals","unknown_table"))
            assertThatIllegalArgumentException().isThrownBy(() -> service.create("test",1,"orders","updated_at",target,"","0 0 * * * ?"))
                    .withMessageContaining("ODS");
        verify(platform, never()).insertPipeline(any());
    }

    @Test void rechecksChangedModelBeforeClaimingRunOrReadingSourceData() {
        var pipeline = new Pipeline(); pipeline.setId(1L); pipeline.setTargetTable("ods_events"); pipeline.setTransformSql("");
        when(platform.lockPipeline(1)).thenReturn(pipeline);
        var model = new DataModel(); model.setLayer("ODS"); model.setModelType("AGGREGATE");
        when(models.modelByTable("ods_events")).thenReturn(model);
        assertThatIllegalArgumentException().isThrownBy(() -> service.claim(1)).withMessageContaining("UNIQUE");
        verify(platform, never()).insertRun(any());
    }

    @Test void legacySqlCannotExecuteUntilMigrated() {
        var pipeline = new Pipeline(); pipeline.setId(1L); pipeline.setTargetTable("ods_orders");
        pipeline.setTransformSql("INSERT INTO ads_orders SELECT id FROM ods_orders");
        when(platform.lockPipeline(1)).thenReturn(pipeline);
        var ods=new DataModel(); ods.setLayer("ODS"); ods.setModelType("UNIQUE");
        when(models.modelByTable("ods_orders")).thenReturn(ods);
        assertThatIllegalArgumentException().isThrownBy(()->service.claim(1)).withMessageContaining("迁移");
        verify(platform,never()).insertRun(any());
    }
}
