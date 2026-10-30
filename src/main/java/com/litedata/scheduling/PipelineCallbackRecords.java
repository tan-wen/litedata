package com.litedata.scheduling;

import com.litedata.model.PipelineRun;
import com.litedata.service.QueryPage;
import com.litedata.store.PlatformMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/** Local callback results are scoped by both the pipeline and the engine workflow instance. */
@Service
public class PipelineCallbackRecords {
    public record Callback(long id,Long taskInstanceId,String status,String startedAt,String finishedAt,
                           String lowerBound,String upperBound,String message) {}
    public record Page(long total,List<Callback> items) {}
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final PlatformMapper mapper;
    public PipelineCallbackRecords(PlatformMapper mapper) { this.mapper=mapper; }
    public long count(long pipelineId,long instanceId) { return mapper.instanceRunCount(pipelineId,instanceId); }
    public Page page(long pipelineId,long instanceId,QueryPage page,long total) {
        return new Page(total,mapper.instanceRunPage(pipelineId,instanceId,page.pageSize(),page.offset()).stream().map(this::display).toList());
    }
    private Callback display(PipelineRun run) {
        return new Callback(run.getId(),run.getTaskInstanceId(),run.getStatus(),beijing(run.getStartedAt()),beijing(run.getFinishedAt()),
                sourceTime(run.getLowerBound()),sourceTime(run.getUpperBound()),run.getMessage()==null?"等待执行节点回传结果":run.getMessage());
    }
    private String sourceTime(LocalDateTime time) { return time==null?"":TIME.format(time); }
    private String beijing(LocalDateTime time) {
        return time==null?"":TIME.format(time.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")));
    }
}
