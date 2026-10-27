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
    private final PipelineService service = new PipelineService(platform, mock(SourceService.class), mock(PlatformProperties.class), models);

    @Test void cannotRegisterReplaySyncOrLegacyTransformAgainstNonUniqueModels() {
        var duplicate = new DataModel(); duplicate.setModelType("DUPLICATE");
        when(models.modelByTable("ods_events")).thenReturn(duplicate);
        assertThatIllegalArgumentException().isThrownBy(() -> service.create("test", 1, "orders", "updated_at", "ods_events", "", "0 0 * * * ?"))
                .withMessageContaining("UNIQUE");
        var aggregate = new DataModel(); aggregate.setModelType("AGGREGATE");
        when(models.modelByTable("ads_totals")).thenReturn(aggregate);
        assertThatIllegalArgumentException().isThrownBy(() -> service.create("test", 1, "orders", "updated_at", "ods_ok",
                "INSERT INTO warehouse.ads_totals SELECT id FROM warehouse.ods_ok", "0 0 * * * ?"))
                .withMessageContaining("UNIQUE");
        verify(platform, never()).insertPipeline(any());
    }

    @Test void rechecksChangedModelBeforeClaimingRunOrReadingSourceData() {
        var pipeline = new Pipeline(); pipeline.setId(1L); pipeline.setTargetTable("ods_events"); pipeline.setTransformSql("");
        when(platform.lockPipeline(1)).thenReturn(pipeline);
        var model = new DataModel(); model.setModelType("AGGREGATE");
        when(models.modelByTable("ods_events")).thenReturn(model);
        assertThatIllegalArgumentException().isThrownBy(() -> service.claim(1)).withMessageContaining("UNIQUE");
        verify(platform, never()).insertRun(any());
    }
}
