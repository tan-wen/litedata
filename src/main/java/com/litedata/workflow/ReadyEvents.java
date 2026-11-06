package com.litedata.workflow;

import com.litedata.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, single-source events. Called in the transaction that records the producer's terminal state. */
@Service
public class ReadyEvents {
    private final ReadyMapper ready;
    private final PlatformMapper platform;

    public ReadyEvents(ReadyMapper ready, PlatformMapper platform) {
        this.ready = ready;
        this.platform = platform;
    }

    @Transactional
    public void publish(long runId, Long qualityRunId) {
        var run = platform.lockRun(runId);
        if (run == null || !"SUCCESS".equals(run.getStatus()))
            throw new IllegalArgumentException("只有成功同步批次可以放行");
        if (run.getQualityTaskId() != null && !java.util.Set.of("PASSED", "WARNING").contains(run.getQualityStatus()))
            throw new IllegalArgumentException("本批质量尚未放行");
        if (!java.util.Objects.equals(run.getQualityRunId(), qualityRunId))
            throw new IllegalArgumentException("质量记录必须属于本次同步批次");
        if (run.getQualityTaskId() != null && (qualityRunId == null || ready.matchingQuality(qualityRunId, runId) == 0))
            throw new IllegalArgumentException("本批缺少已通过的关联质量记录");
        if (ready.existingEvent(runId) != null) return;
        ready.event(run.getPipelineId(), runId, qualityRunId);
        ready.subscribers(ready.eventId(runId), run.getPipelineId());
    }
}
