package com.litedata.workflow;

import com.litedata.quality.QualityService;
import com.litedata.store.*;
import com.litedata.transform.DolphinWorkflowClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Single-instance durable queue; ambiguous engine submissions are retained for reconciliation. */
@Service
public class ReadinessDispatcher {
    private final ReadyMapper ready;
    private final PlatformMapper platform;
    private final WorkflowMapper workflows;
    private final SqlTaskMapper sql;
    private final QualityService quality;
    private final WorkflowScheduler scheduler;
    private final DolphinWorkflowClient engine;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private volatile boolean started;

    public ReadinessDispatcher(ReadyMapper ready, PlatformMapper platform, WorkflowMapper workflows, SqlTaskMapper sql,
                               QualityService quality, WorkflowScheduler scheduler, DolphinWorkflowClient engine,
                               org.springframework.transaction.PlatformTransactionManager manager,
                               @Value("${platform.readiness.scheduling-enabled:true}") boolean enabled) {
        this.ready = ready;
        this.platform = platform;
        this.workflows = workflows;
        this.sql = sql;
        this.quality = quality;
        this.scheduler = scheduler;
        this.engine = engine;
        this.transactions = new TransactionTemplate(manager);
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        ready.recover();
        started = true;
    }

    @Scheduled(fixedDelayString = "${platform.readiness.poll-ms:3000}")
    public void poll() {
        if (!started || !enabled) return;
        try {
            tick();
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Database unavailable: all claims/events remain durable for the next poll.
        }
    }

    public synchronized void tick() {
        for (long runId : ready.waitingQuality()) {
            try {
                quality.startPipeline(runId);
            } catch (org.springframework.dao.TransientDataAccessException ignored) {
                // A contested database claim is safe to retry before starting a scan.
            } catch (IllegalArgumentException e) {
                transactions.executeWithoutResult(tx -> {
                    platform.lockRun(runId);
                    ready.scanFinished(runId, "ERROR");
                    platform.audit("readiness", "QUALITY_GATE_ERROR", Long.toString(runId), "ERROR");
                });
            }
        }
        for (long deliveryId : ready.pending()) deliver(deliveryId);
    }

    public void retry(long workflowId, long deliveryId, String actor) {
        transactions.executeWithoutResult(tx -> {
            sql.lockGraph();
            var delivery = ready.lockDelivery(deliveryId);
            var workflow = workflows.workflow(workflowId);
            if (delivery == null || delivery.workflowId() != workflowId || !"FAILED".equals(delivery.state()))
                throw new IllegalArgumentException("仅可重试本流程尚未提交到引擎的失败事件");
            if (workflow == null || !"EVENT_ENABLED".equals(workflow.getStatus()) || !"EVENT".equals(workflow.getTriggerMode()) ||
                !java.util.Objects.equals(workflow.getTriggerPipelineId(), delivery.pipelineId()))
                throw new IllegalArgumentException("请先启用原来源的事件订阅");
            ready.retry(deliveryId, workflow.getTriggerGeneration());
            platform.audit(actor, "DATA_READY_RETRY", Long.toString(deliveryId), "ACCEPTED");
        });
    }

    private void deliver(long id) {
        Long code;
        try {
            code = transactions.execute(tx -> {
                sql.lockGraph();
                var delivery = ready.lockDelivery(id);
                if (delivery == null || !"PENDING".equals(delivery.state())) return null;
                var workflow = workflows.workflow(delivery.workflowId());
                if (workflow == null || !"EVENT_ENABLED".equals(workflow.getStatus()) ||
                    !"EVENT".equals(workflow.getTriggerMode()) || workflow.getTriggerGeneration() != delivery.triggerGeneration()) {
                    ready.state(id, "CANCELLED", "订阅已暂停、归档或重新启用", null);
                    return null;
                }
                Long definition = scheduler.prepareEvent(delivery.workflowId());
                if (definition == null) return null;
                ready.state(id, "SUBMITTING", "正在向引擎提交", definition);
                return definition;
            });
        } catch (org.springframework.dao.TransientDataAccessException ignored) {
            return;
        } catch (IllegalArgumentException | org.springframework.web.server.ResponseStatusException e) {
            ready.state(id, "FAILED", "加工配置校验失败；修复并启用订阅后，可重试本条未提交事件", null);
            return;
        } catch (IllegalStateException e) {
            // Validation could not reach an external service; no start request was made.
            return;
        }
        if (code == null) return;
        try {
            transactions.executeWithoutResult(tx -> {
                // Serialize the final check and network request with pause/edit operations.
                sql.lockGraph();
                var delivery = ready.lockDelivery(id);
                var workflow = workflows.workflow(delivery.workflowId());
                if (!"SUBMITTING".equals(delivery.state())) return;
                if (workflow == null || !"EVENT_ENABLED".equals(workflow.getStatus()) ||
                    workflow.getTriggerGeneration() != delivery.triggerGeneration()) {
                    ready.state(id, "CANCELLED", "提交前订阅已暂停或变更", code);
                    return;
                }
                engine.start(code);
                ready.state(id, "SUBMITTED", "引擎已接受；实际加工结果请查看运行记录", code);
                platform.audit("readiness", "DATA_READY_SUBMIT", Long.toString(id), "ACCEPTED");
            });
        } catch (RuntimeException e) {
            ready.state(id, "UNKNOWN", "引擎提交结果未知；请先核对引擎实例，勿自动重复提交", code);
            return;
        }
    }
}
