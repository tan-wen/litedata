package com.litedata.workflow;

import com.litedata.store.WorkflowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pause old schedules whose shared SQL membership was detached by migration. Retry engine outages.
 */
@Component
public class WorkflowOwnershipReconciler {
    private static final Logger log = LoggerFactory.getLogger(WorkflowOwnershipReconciler.class);
    private final WorkflowMapper workflows;
    private final WorkflowScheduler scheduler;

    public WorkflowOwnershipReconciler(WorkflowMapper workflows, WorkflowScheduler scheduler) {
        this.workflows = workflows;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${platform.workflow.ownership-reconcile-ms:30000}")
    public void pausePendingReviews() {
        for (var workflow : workflows.pendingOwnershipPause()) {
            try {
                scheduler.pause(workflow.getId(), "ownership-migration");
            } catch (RuntimeException failure) {
                log.warn("Unable to pause workflow {} awaiting ownership review; will retry", workflow.getId());
            }
        }
    }
}
