package com.litedata.workflow;

import com.litedata.store.PlatformMapper;
import com.litedata.store.WorkflowMapper;
import com.litedata.store.SqlTaskMapper;
import com.litedata.transform.*;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowScheduler {
    private final WorkflowMapper mapper;
    private final SqlTaskMapper sql;
    private final WorkflowGraph graphs;
    private final PlatformMapper platform;
    private final TransformService tasks;
    private final DolphinWorkflowClient engine;
    private final WorkflowSnapshots snapshots;

    public WorkflowScheduler(WorkflowMapper mapper, SqlTaskMapper sql, PlatformMapper platform, TransformService tasks, DolphinWorkflowClient engine, WorkflowSnapshots snapshots, WorkflowGraph graphs) {
        this.mapper = mapper;
        this.sql = sql;
        this.platform = platform;
        this.tasks = tasks;
        this.engine = engine;
        this.snapshots = snapshots;
        this.graphs = graphs;
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    public void run(long id, String actor) {
        sql.lockGraph();
        requireReviewed(id);
        if ("EVENT".equals(mapper.workflow(id).getTriggerMode()))
            throw new IllegalArgumentException("事件模式由数据就绪事件放行，请运行来源同步任务");
        var graph = graphs.graph(id);
        preflight(graph);
        long code = definition(id, graph);
        engine.publish(code);
        engine.start(code);
        platform.audit(actor, "WORKFLOW_SUBMIT", Long.toString(id), "ACCEPTED");
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    public void schedule(long id, String actor) {
        sql.lockGraph();
        requireReviewed(id);
        var graph = graphs.graph(id);
        preflight(graph);
        long code = definition(id, graph);
        engine.publish(code);
        var workflow = mapper.workflow(id);
        if ("EVENT".equals(workflow.getTriggerMode())) {
            if (workflow.getTriggerPipelineId() == null || platform.pipeline(workflow.getTriggerPipelineId()) == null)
                throw new IllegalArgumentException("请选择有效的数据就绪来源");
            if (!"EVENT_ENABLED".equals(workflow.getStatus())) mapper.enableEvents(id);
            platform.audit(actor, "WORKFLOW_EVENT_ENABLE", Long.toString(id), "SUCCESS");
            return;
        }
        Integer scheduleId = workflow.getScheduleId();
        if (scheduleId == null) {
            scheduleId = engine.createSchedule(code, workflow.getCronExpression());
            mapper.schedule(id, scheduleId, "SCHEDULE_PENDING");
        }
        engine.online(scheduleId);
        mapper.schedule(id, scheduleId, "SCHEDULED");
        platform.audit(actor, "WORKFLOW_SCHEDULE", Long.toString(id), "SUCCESS");
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    public void pause(long id, String actor) {
        sql.lockGraph();
        var workflow = mapper.workflow(id);
        if (workflow.getScheduleId() != null && !"PAUSED".equals(workflow.getStatus()))
            engine.offline(workflow.getScheduleId());
        mapper.schedule(id, workflow.getScheduleId(), "PAUSED");
        platform.audit(actor, "WORKFLOW_PAUSE", Long.toString(id), "SUCCESS");
    }

    @Transactional
    public Long prepareEvent(long id) {
        sql.lockGraph();
        requireReviewed(id);
        var workflow = mapper.workflow(id);
        if (!"EVENT".equals(workflow.getTriggerMode()) || !"EVENT_ENABLED".equals(workflow.getStatus()))
            throw new IllegalArgumentException("订阅已暂停或变更");
        var graph = graphs.graph(id);
        for (var node : graph.nodes())
            if (sql.task(node.refId()).getActiveRunId() != null) return null;
        preflight(graph);
        if (workflow.getDefinitionCode() == null || !graph.hash().equals(workflow.getDefinitionHash()))
            throw new IllegalArgumentException("加工定义已变化，请暂停后重新启用订阅");
        return workflow.getDefinitionCode();
    }

    private void preflight(TransformService.Graph graph) {
        if (graph.nodes().isEmpty()) throw new IllegalArgumentException("请先在画布中添加任务并保存后再运行或启用调度");
        tasks.preflight(graph);
    }

    private void requireReviewed(long id) {
        var workflow = mapper.workflow(id);
        if (workflow == null || workflow.isReviewRequired())
            throw new IllegalArgumentException("加工任务不存在或迁移尚未核对，无法运行或启用调度");
    }

    private long definition(long id, TransformService.Graph graph) {
        if (!engine.configured()) throw new IllegalStateException("调度引擎尚未配置，请先完成引擎初始化");
        var workflow = mapper.workflow(id);
        var legacy = mapper.legacyBinding(id);
        boolean current = graph.hash().equals(workflow.getDefinitionHash());
        // An untouched upgraded workflow can keep its existing definition and online schedule.
        // Its workers still use the old root/hash contract until the first topology or SQL edit.
        if (!current && legacy != null && legacy.graphHash() != null && legacy.graphHash().equals(workflow.getDefinitionHash()))
            current = legacy.graphHash().equals(tasks.graph(legacy.rootTaskId()).hash());
        if (workflow.getDefinitionCode() != null && current) {
            remember(graph, workflow.getDefinitionCode());
            return workflow.getDefinitionCode();
        }
        if (workflow.getScheduleId() != null && !"PAUSED".equals(workflow.getStatus()))
            engine.offline(workflow.getScheduleId());
        var nodes = graph.nodes().stream().map(node -> {
            String script = node.kind().equals("同步")
                ? "python3 /opt/platform/bin/run_pipeline.py --pipeline-id " + node.refId()
                + " --workflow-instance-id ${system.workflow.instance.id} --task-instance-id ${system.task.instance.id}"
                : "python3 /opt/platform/bin/run_sql_task.py --task-id " + node.refId() + " --version " + node.version()
                + " --workflow-id " + id + " --graph-hash " + graph.hash();
            return new DolphinWorkflowClient.Node(node.key(), node.kind() + "：" + node.name(), script, node.upstreamKeys());
        }).toList();
        long code = engine.create("platform-workflow-" + id + "-" + graph.hash().substring(0, 12), workflow.getName(), nodes);
        mapper.retireLegacy(id);
        mapper.definition(id, code, graph.hash());
        snapshots.remember(id, code, graph);
        remember(graph, code);
        return code;
    }

    private void remember(TransformService.Graph graph, long code) {
        for (var node : graph.nodes())
            platform.rememberWorkflow(node.kind().equals("同步") ? "pipeline" : "transform", node.refId(), code);
    }
}
