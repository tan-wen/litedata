package com.litedata.workflow;

import com.litedata.model.SqlTask;
import com.litedata.service.QueryPage;
import com.litedata.scheduling.SchedulePreview;
import com.litedata.store.*;
import com.litedata.transform.*;

import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkflowService {
    public record Position(double x, double y) {
    }

    public record Node(String key, String name, String kind, long refId, int version, List<String> upstreamKeys,
                       String tableName, String editUrl, String engineName, String configHash,
                       boolean standaloneScheduled) {
    }

    public record Edge(String from, String to) {
    }

    public record View(long id, String name, String description, int version, String cronExpression, String status,
                       Integer scheduleId, Long definitionCode, String graphHash, List<Node> nodes,
                       Map<String, Position> positions,
                       boolean reviewRequired, String ownershipNotice) {
    }

    public record SqlDraft(String key, Long taskId, int version, String name, Long modelId, String sqlText) {
    }

    public record Save(int version, String graphHash, String cronExpression, List<Node> nodes, List<Edge> edges,
                       Map<String, Position> positions,
                       Boolean acknowledgeOwnershipMigration, List<SqlDraft> sqlDrafts) {
        public Save(int version, String graphHash, String cronExpression, List<Node> nodes, List<Edge> edges, Map<String, Position> positions) {
            this(version, graphHash, cronExpression, nodes, edges, positions, false, List.of());
        }

        public Save(int version, String graphHash, String cronExpression, List<Node> nodes, List<Edge> edges, Map<String, Position> positions, Boolean acknowledgeOwnershipMigration) {
            this(version, graphHash, cronExpression, nodes, edges, positions, acknowledgeOwnershipMigration, List.of());
        }
    }

    public record ModelChoice(long id, String name, String tableName, Long taskId, List<String> columns) {
    }

    public List<ModelChoice> modelChoices(List<com.litedata.model.DataModel> models) {
        var writers = new HashMap<Long, Long>();
        for (var t : sql.tasks()) writers.put(t.getModelId(), t.getId());
        var columns = new HashMap<Long, List<String>>();
        for (var field : mapper.sqlModelFields())
            columns.computeIfAbsent(field.modelId(), ignored -> new ArrayList<>()).add(field.name());
        return models.stream().filter(m -> !"ODS".equals(m.getLayer()) && "UNIQUE".equals(m.getModelType()))
            .map(m -> new ModelChoice(m.getId(), m.getName(), m.getTableName(), writers.get(m.getId()),
                List.copyOf(columns.getOrDefault(m.getId(), List.of())))).toList();
    }

    public record TaskSave(int workflowVersion, String graphHash, Long taskId, int version, String name, Long modelId,
                           String sqlText) {
    }

    public record TaskDefinition(long id, String name, Long modelId, String sqlText, int version) {
    }

    public record Catalog(List<WorkflowMapper.CatalogItem> items, long total, int pageNum, int pageSize) {
    }

    private final WorkflowMapper mapper;
    private final SqlTaskMapper sql;
    private final PlatformMapper platform;
    private final TransformService tasks;
    private final WorkflowScheduler scheduler;
    private final WorkflowGraph graphs;
    private final JsonMapper json = JsonMapper.builder().build();

    public WorkflowService(WorkflowMapper mapper, SqlTaskMapper sql, PlatformMapper platform, TransformService tasks, WorkflowScheduler scheduler, WorkflowGraph graphs) {
        this.mapper = mapper;
        this.sql = sql;
        this.platform = platform;
        this.tasks = tasks;
        this.scheduler = scheduler;
        this.graphs = graphs;
    }

    public ProcessingWorkflow require(long id) {
        var workflow = mapper.workflow(id);
        if (workflow == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在");
        return workflow;
    }

    public List<ProcessingWorkflow> forTask(long id) {
        return mapper.forTask(id);
    }

    public long count(String keyword) {
        return mapper.count(query(keyword));
    }

    public List<ProcessingWorkflow> list(String keyword, QueryPage page) {
        return mapper.page(query(keyword), page.pageSize(), page.offset());
    }

    public Catalog catalog(long id, String keyword, QueryPage page) {
        require(id);
        return new Catalog(mapper.catalog(query(keyword), page.pageSize(), page.offset()), mapper.catalogCount(query(keyword)), page.pageNum(), page.pageSize());
    }

    @Transactional
    public long metadata(Long id, ProcessingWorkflow draft, String actor) {
        sql.lockGraph();
        if (draft.getName() == null || draft.getName().isBlank() || draft.getName().trim().length() > 100)
            throw new IllegalArgumentException("工作流名称不能为空，最长100字符");
        draft.setName(draft.getName().trim());
        draft.setDescription(Objects.toString(draft.getDescription(), "").trim());
        if (draft.getDescription().length() > 500) throw new IllegalArgumentException("描述最长500字符");
        if (id == null) {
            draft.setId(null);
            mapper.insert(draft);
            id = draft.getId();
        } else {
            version(require(id), draft.getVersion());
            draft.setId(id);
            mapper.metadata(draft);
        }
        platform.audit(actor, "WORKFLOW_SAVE", Long.toString(id), "SUCCESS");
        return id;
    }

    @Transactional
    public void archive(long id, int expected, String actor) {
        sql.lockGraph();
        var workflow = require(id);
        version(workflow, expected);
        ensureEditable(workflow);
        mapper.retireLegacy(id);
        for (String key : mapper.nodeKeys(id))
            if (key.startsWith("sql-")) {
                long taskId = WorkflowGraph.parse(key);
                sql.deleteLegacyReferences(taskId);
                sql.deletePipelineDependencies(taskId);
            }
        sql.archiveOwned(id);
        mapper.archive(id);
        platform.audit(actor, "WORKFLOW_ARCHIVE", Long.toString(id), "SUCCESS");
    }

    public View view(long id) {
        var workflow = require(id);
        var graph = graphs.graph(id);
        var nodes = graph.nodes().stream().map(n -> graphs.node(id, n.key(), n.upstreamKeys())).toList();
        var positions = new LinkedHashMap<>(positions(workflow.getPositionsJson()));
        positions.keySet().retainAll(nodes.stream().map(Node::key).toList());
        return view(workflow, graph.hash(), nodes, positions);
    }

    public View summary(long id) {
        return view(require(id), "", List.of(), Map.of());
    }

    private View view(ProcessingWorkflow w, String hash, List<Node> nodes, Map<String, Position> positions) {
        return new View(w.getId(), w.getName(), w.getDescription(), w.getVersion(), w.getCronExpression(), w.getStatus(),
            w.getScheduleId(), w.getDefinitionCode(), hash, nodes, positions, w.isReviewRequired(), w.getOwnershipNotice());
    }

    public List<Node> expand(long id, String key) {
        require(id);
        return List.of(graphs.node(id, key, List.of()));
    }

    public TaskDefinition task(long workflowId, long taskId) {
        require(workflowId);
        if (!mapper.nodeKeys(workflowId).contains("sql-" + taskId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不在此工作流中");
        var t = tasks.require(taskId);
        return new TaskDefinition(t.getId(), t.getName(), t.getModelId(), t.getSqlText(), t.getVersion());
    }

    @Transactional
    public View saveTask(long id, TaskSave input, String actor) {
        sql.lockGraph();
        var workflow = require(id);
        version(workflow, input.workflowVersion());
        checkHash(id, input.graphHash());
        ensureEditable(workflow);
        requireReviewed(workflow);
        if (input.taskId() != null) task(id, input.taskId());
        else if (mapper.nodeKeys(id).size() >= 100) throw new IllegalArgumentException("工作流最多100个节点");
        var draft = input.taskId() == null ? new SqlTask() : tasks.require(input.taskId());
        draft.setWorkflowId(id);
        draft.setName(input.name());
        draft.setModelId(input.modelId());
        draft.setSqlText(input.sqlText());
        draft.setVersion(input.version());
        long taskId = tasks.save(input.taskId(), draft, actor);
        platform.audit(actor, "WORKFLOW_SQL_SAVE", id + ":" + taskId, "SUCCESS");
        return view(id);
    }

    @Transactional
    public View save(long id, Save input, String actor) {
        sql.lockGraph();
        var workflow = require(id);
        version(workflow, input.version());
        checkHash(id, input.graphHash());
        String cron = SchedulePreview.validate(input.cronExpression());
        if (input.nodes() == null || input.nodes().size() > 100)
            throw new IllegalArgumentException("工作流最多100个节点");
        var existingKeys = mapper.nodeKeys(id);
        var drafts = input.sqlDrafts() == null ? List.<SqlDraft>of() : input.sqlDrafts();
        if (drafts.size() > input.nodes().size()) throw new IllegalArgumentException("SQL 草稿必须属于当前画布节点");
        var definitions = new LinkedHashMap<String, SqlDraft>();
        for (var draft : drafts) {
            if (draft == null || draft.key() == null || definitions.putIfAbsent(draft.key(), draft) != null)
                throw new IllegalArgumentException("SQL 草稿节点无效或重复");
        }
        var aliases = new LinkedHashMap<String, String>();
        var prepared = new LinkedHashMap<String, SqlTask>();
        long provisional = 999999999999999999L;
        var rawKeys = input.nodes().stream().filter(Objects::nonNull).map(Node::key).collect(java.util.stream.Collectors.toSet());
        for (var node : input.nodes()) {
            if (node == null || node.key() == null || aliases.containsKey(node.key()))
                throw new IllegalArgumentException("节点无效或重复");
            var draft = definitions.get(node.key());
            if (node.key().startsWith("draft-")) {
                if (!node.key().matches("draft-[A-Za-z0-9-]{1,64}") || !"SQL".equals(node.kind()) || node.refId() != 0 || node.version() != 0
                    || draft == null || draft.taskId() != null || draft.version() != 0)
                    throw new IllegalArgumentException("新增 SQL 草稿节点无效");
                String placeholder;
                do {
                    placeholder = "sql-" + provisional--;
                } while (rawKeys.contains(placeholder) || aliases.containsValue(placeholder));
                aliases.put(node.key(), placeholder);
            } else {
                WorkflowGraph.parse(node.key());
                if (node.key().startsWith("sql-") && !existingKeys.contains(node.key()))
                    throw new IllegalArgumentException("SQL 任务必须属于当前画布");
                var current = graphs.node(id, node.key(), List.of());
                if (node.version() != current.version() || !Objects.equals(node.configHash(), current.configHash()))
                    throw conflict();
                if (draft != null && (!node.key().startsWith("sql-") || !Objects.equals(draft.taskId(), current.refId())))
                    throw new IllegalArgumentException("SQL 草稿与节点不匹配");
                if (draft != null && draft.version() != current.version()) throw conflict();
                aliases.put(node.key(), node.key());
            }
            if (draft != null) {
                var definition = new SqlTask();
                definition.setId(draft.taskId());
                definition.setWorkflowId(id);
                definition.setVersion(draft.version());
                definition.setName(draft.name());
                definition.setModelId(draft.modelId());
                definition.setSqlText(draft.sqlText());
                prepared.put(draft.key(), tasks.prepareDefinition(definition));
            }
        }
        if (!aliases.keySet().containsAll(definitions.keySet()))
            throw new IllegalArgumentException("SQL 草稿必须属于当前画布节点");
        var validatedEdges = remapEdges(input.edges(), aliases);
        var upstream = WorkflowGraph.upstream(aliases.values(), validatedEdges);
        boolean changed = !upstream.equals(WorkflowGraph.upstream(existingKeys, mapper.edges(id)));
        boolean cronChanged = !workflow.getCronExpression().equals(cron);
        boolean acknowledged = Boolean.TRUE.equals(input.acknowledgeOwnershipMigration());
        if (!drafts.isEmpty() && workflow.isReviewRequired() && !acknowledged) requireReviewed(workflow);
        if (changed || cronChanged || !drafts.isEmpty() || workflow.isReviewRequired() && acknowledged)
            ensureEditable(workflow);
        var positions = input.positions() == null ? Map.<String, Position>of() : input.positions();
        if (positions.size() > 100) throw new IllegalArgumentException("布局节点过多");
        for (var entry : positions.entrySet()) {
            var point = entry.getValue();
            if (!aliases.containsKey(entry.getKey()) || point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                || point.x() < 0 || point.y() < 0 || point.x() > 50000 || point.y() > 50000)
                throw new IllegalArgumentException("节点位置无效");
        }
        // Check the final set, allowing models/names released elsewhere in the same draft.
        var names = new HashSet<String>();
        var modelIds = new HashSet<Long>();
        for (String key : aliases.keySet())
            if (key.startsWith("sql-") || key.startsWith("draft-")) {
                var definition = prepared.containsKey(key) ? prepared.get(key) : sql.task(WorkflowGraph.parse(key));
                if (!names.add(definition.getName().toLowerCase(Locale.ROOT)) || !modelIds.add(definition.getModelId()))
                    throw new IllegalArgumentException("同一画布中的 SQL 名称和目标模型不能重复");
            }
        if (changed || !drafts.isEmpty()) mapper.retireLegacy(id);
        var removed = new HashSet<>(existingKeys);
        removed.removeAll(aliases.keySet());
        for (String key : removed)
            if (key.startsWith("sql-")) {
                long taskId = WorkflowGraph.parse(key);
                sql.deleteLegacyReferences(taskId);
                sql.deletePipelineDependencies(taskId);
                sql.archive(taskId);
            }
        // Release unique active name/model slots first, so two edited tasks can swap them.
        // All staging, replacement and graph changes are invisible until this transaction commits.
        for (var definition : prepared.values()) if (definition.getId() != null) sql.archive(definition.getId());
        for (var entry : prepared.entrySet()) {
            var definition = entry.getValue();
            if (definition.getId() == null) sql.insert(definition);
            else sql.replaceDraftDefinition(definition);
            aliases.put(entry.getKey(), "sql-" + definition.getId());
            platform.audit(actor, "SQL_TASK_SAVE", Long.toString(definition.getId()), "SUCCESS");
            platform.audit(actor, "WORKFLOW_SQL_SAVE", id + ":" + definition.getId(), "SUCCESS");
        }
        if (changed) {
            mapper.deleteEdges(id);
            mapper.deleteNodes(id);
            for (String key : aliases.values())
                mapper.node(id, key, key.startsWith("sql-") ? WorkflowGraph.parse(key) : null, key.startsWith("pipeline-") ? WorkflowGraph.parse(key) : null);
            for (var item : remapEdges(input.edges(), aliases)) mapper.edge(id, item.from(), item.to());
        }
        if (cronChanged) mapper.schedule(id, null, "DRAFT");
        if (workflow.isReviewRequired() && acknowledged) mapper.acknowledgeOwnership(id);
        var valid = new LinkedHashMap<String, Position>();
        positions.forEach((key, point) -> valid.put(aliases.get(key), point));
        mapper.graph(id, json.writeValueAsString(valid), cron);
        platform.audit(actor, "WORKFLOW_GRAPH_SAVE", Long.toString(id), "SUCCESS");
        return view(id);
    }

    private static List<Edge> remapEdges(List<Edge> edges, Map<String, String> aliases) {
        if (edges == null || edges.size() > 10000) throw new IllegalArgumentException("工作流连线无效");
        var result = new ArrayList<Edge>();
        for (var edge : edges) {
            if (edge == null || !aliases.containsKey(edge.from()) || !aliases.containsKey(edge.to()))
                throw new IllegalArgumentException("连线须连接画布中的节点");
            result.add(new Edge(aliases.get(edge.from()), aliases.get(edge.to())));
        }
        return result;
    }

    private void ensureEditable(ProcessingWorkflow workflow) {
        if (Set.of("SCHEDULED", "SCHEDULE_PENDING").contains(workflow.getStatus()))
            throw new IllegalArgumentException("请先暂停此工作流的定时调度，再修改节点、依赖或 Cron");
        for (String key : mapper.nodeKeys(workflow.getId())) {
            boolean active = key.startsWith("sql-") ? tasks.require(WorkflowGraph.parse(key)).getActiveRunId() != null : platform.pipeline(WorkflowGraph.parse(key)).getActiveRunId() != null;
            if (active) throw new IllegalArgumentException("工作流存在运行中的节点，请等待完成");
        }
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    public void execute(long id, int expected, String graphHash, String action, String actor) {
        sql.lockGraph();
        var workflow = require(id);
        version(workflow, expected);
        if (action.equals("pause")) {
            scheduler.pause(id, actor);
            return;
        }
        requireReviewed(workflow);
        checkHash(id, graphHash);
        switch (action) {
            case "run" -> scheduler.run(id, actor);
            case "schedule" -> scheduler.schedule(id, actor);
            default -> throw new IllegalArgumentException("操作无效");
        }
    }

    private void checkHash(long id, String hash) {
        if (!Objects.equals(graphs.graph(id).hash(), hash)) throw conflict();
    }

    private static void requireReviewed(ProcessingWorkflow workflow) {
        if (workflow.isReviewRequired())
            throw new IllegalArgumentException("请先核对迁移后的输入表和依赖，勾选确认并保存画布");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Position> positions(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String, Object> raw = json.readValue(value, Map.class);
        var result = new LinkedHashMap<String, Position>();
        raw.forEach((key, point) -> {
            var p = (Map<String, Number>) point;
            result.put(key, new Position(p.get("x").doubleValue(), p.get("y").doubleValue()));
        });
        return result;
    }

    private static String query(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static void version(ProcessingWorkflow value, int expected) {
        if (value.getVersion() != expected) throw conflict();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "工作流或任务配置已变化，请刷新后重新编排");
    }
}
