package com.litedata.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.*;

import com.litedata.model.SqlTask;
import com.litedata.store.LineageMapper;
import com.litedata.store.MetadataMapper;
import com.litedata.store.PlatformMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineageServiceTest {
    @Test void boundsNodeExpansionAt120WithoutReturningAnUnboundedGraph() {
        Fixture fixture = fixture("root");
        var tasks = new ArrayList<SqlTask>();
        for (int index = 0; index < 121; index++) tasks.add(task(index + 1L, "fan-" + index, "INSERT INTO node_" + index + " SELECT id FROM root"));
        when(fixture.mapper.tasks()).thenReturn(tasks);

        LineageService.Graph graph = fixture.service.graph(1L, "downstream", 1);

        assertThat(graph.truncated()).isTrue();
        assertThat(graph.nodes()).hasSize(120);
        assertThat(graph.edges()).hasSize(119);
    }

    @Test void boundsSelectedEdgesAt300() {
        Fixture fixture = fixture("root");
        var tasks = new ArrayList<SqlTask>();
        for (int index = 0; index < 301; index++) tasks.add(task(index + 1L, "edge-" + index, "INSERT INTO sink SELECT id FROM root"));
        when(fixture.mapper.tasks()).thenReturn(tasks);

        LineageService.Graph graph = fixture.service.graph(1L, "downstream", 1);

        assertThat(graph.truncated()).isTrue();
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(300);
    }

    @Test void parsesSqlAndKeepsDiamondDuplicatesAndCyclesFiniteWhileRespectingDirectionAndDepth() {
        Fixture fixture = fixture("dwd_orders");
        when(fixture.mapper.tasks()).thenReturn(List.of(
                task(1, "to-root", "INSERT INTO dwd_orders SELECT id FROM ods_orders"),
                task(2, "to-root", "INSERT INTO dwd_orders SELECT id FROM ods_orders"),
                task(3, "left", "INSERT INTO dws_left SELECT id FROM dwd_orders"),
                task(4, "right", "INSERT INTO dws_right SELECT id FROM dwd_orders"),
                task(5, "ads-left", "INSERT INTO ads_orders SELECT id FROM dws_left"),
                task(6, "ads-right", "INSERT INTO ads_orders SELECT id FROM dws_right"),
                task(7, "cycle", "INSERT INTO dwd_orders SELECT id FROM ads_orders"),
                task(8, "cte", "INSERT INTO ads_cte WITH chosen AS (SELECT id FROM dwd_orders) SELECT id FROM chosen")));

        LineageService.Graph upstream = fixture.service.graph(1L, "upstream", 1);
        LineageService.Graph downstream = fixture.service.graph(1L, "downstream", 1);
        LineageService.Graph both = fixture.service.graph(1L, "both", 6);

        assertThat(upstream.nodes()).extracting(LineageService.Node::table).containsExactlyInAnyOrder("warehouse.dwd_orders", "warehouse.ods_orders", "warehouse.ads_orders");
        assertThat(downstream.nodes()).extracting(LineageService.Node::table).containsExactlyInAnyOrder("warehouse.dwd_orders", "warehouse.dws_left", "warehouse.dws_right", "warehouse.ads_cte");
        assertThat(both.nodes()).hasSize(6);
        assertThat(both.edges()).hasSize(8);
        assertThat(both.nodes()).extracting(LineageService.Node::table).doesNotContain("warehouse.chosen");
        assertThat(both.edges()).anySatisfy(edge -> {
            assertThat(edge.from()).isEqualTo("doris/warehouse/dwd_orders");
            assertThat(edge.to()).isEqualTo("doris/warehouse/ads_cte");
            assertThat(edge.task()).isEqualTo("cte");
        });
    }

    @Test void rejectsIndexesBeyondTheTaskSafetyLimit() {
        Fixture fixture = fixture("root");
        var tooMany = new ArrayList<SqlTask>();
        for (int index = 0; index < 5_001; index++) tooMany.add(task(index + 1L, "task-" + index, "INSERT INTO sink SELECT id FROM root"));
        when(fixture.mapper.tasks()).thenReturn(tooMany);

        assertThatIllegalArgumentException().isThrownBy(() -> fixture.service.graph(1L, "both", 3)).withMessageContaining("最多5000条");
    }

    private static Fixture fixture(String rootTable) {
        MetadataMapper metadata = mock(MetadataMapper.class);
        LineageMapper mapper = mock(LineageMapper.class);
        PlatformMapper platform = mock(PlatformMapper.class);
        MetadataTable root = new MetadataTable();
        root.setId(1L); root.setScopeKey("doris"); root.setCatalogName("warehouse"); root.setTableName(rootTable); root.setSourceName("Doris 数仓");
        when(metadata.table(1L)).thenReturn(root);
        when(metadata.find(anyString(), anyString(), anyString())).thenReturn(null);
        when(platform.sources()).thenReturn(List.of());
        when(mapper.pipelines()).thenReturn(List.of());
        when(mapper.tasks()).thenReturn(List.of());
        return new Fixture(new LineageService(metadata, mapper, platform), mapper);
    }

    private static SqlTask task(long id, String name, String sql) {
        SqlTask task = new SqlTask();
        task.setId(id); task.setName(name); task.setSqlText(sql);
        return task;
    }

    private record Fixture(LineageService service, LineageMapper mapper) {}
}
