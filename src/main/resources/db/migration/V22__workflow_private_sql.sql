-- One SQL definition belongs to one workflow. Do not clone writers of the same target.
ALTER TABLE processing_workflow
 ADD review_required BOOLEAN NOT NULL DEFAULT FALSE,
 ADD ownership_notice VARCHAR(1000) NOT NULL DEFAULT '',
 ADD legacy_sql_task_id BIGINT;
ALTER TABLE sql_task ADD workflow_id BIGINT;

-- Retain otherwise unowned SQL definitions in their own draft workflow.
INSERT INTO processing_workflow(name,description,archived,legacy_sql_task_id)
 SELECT CONCAT('历史加工-',t.id,'-',LEFT(UUID(),8)),'历史 SQL 任务迁移生成的独立加工流程',t.archived,t.id
 FROM sql_task t WHERE NOT EXISTS(SELECT 1 FROM processing_workflow_node n WHERE n.task_id=t.id);
UPDATE sql_task t
 LEFT JOIN (SELECT n.task_id,MIN(CASE WHEN w.archived=FALSE THEN n.workflow_id END) active_owner,
                   MIN(n.workflow_id) historic_owner
            FROM processing_workflow_node n JOIN processing_workflow w ON w.id=n.workflow_id
            WHERE n.task_id IS NOT NULL GROUP BY n.task_id) refs ON refs.task_id=t.id
 LEFT JOIN processing_workflow created ON created.legacy_sql_task_id=t.id
 SET t.workflow_id=COALESCE(refs.active_owner,refs.historic_owner,created.id);
INSERT INTO processing_workflow_node(workflow_id,node_key,task_id)
 SELECT t.workflow_id,CONCAT('sql-',t.id),t.id FROM sql_task t
 WHERE t.archived=FALSE AND NOT EXISTS(SELECT 1 FROM processing_workflow_node n WHERE n.task_id=t.id);
-- Preserve direct synchronization prerequisites of formerly standalone tasks.
INSERT INTO processing_workflow_node(workflow_id,node_key,pipeline_id)
 SELECT w.id,CONCAT('pipeline-',d.pipeline_id),d.pipeline_id FROM processing_workflow w
 JOIN sql_task t ON t.id=w.legacy_sql_task_id AND t.archived=FALSE
 JOIN sql_pipeline_dependency d ON d.task_id=t.id;
INSERT INTO processing_workflow_edge(workflow_id,from_key,to_key)
 SELECT w.id,CONCAT('pipeline-',d.pipeline_id),CONCAT('sql-',t.id) FROM processing_workflow w
 JOIN sql_task t ON t.id=w.legacy_sql_task_id AND t.archived=FALSE
 JOIN sql_pipeline_dependency d ON d.task_id=t.id;

-- Keep the original topology for review and historical investigation before detaching shared nodes.
CREATE TABLE workflow_sql_ownership_migration (
 workflow_id BIGINT PRIMARY KEY,
 original_nodes_json JSON NOT NULL,
 original_edges_json JSON NOT NULL,
 removed_nodes_json JSON NOT NULL,
 legacy_dependencies_json JSON NOT NULL,
 positions_json MEDIUMTEXT,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_wf_ownership_audit FOREIGN KEY(workflow_id) REFERENCES processing_workflow(id) ON DELETE CASCADE
);
INSERT INTO workflow_sql_ownership_migration(workflow_id,original_nodes_json,original_edges_json,removed_nodes_json,legacy_dependencies_json,positions_json)
 SELECT w.id,
  (SELECT JSON_ARRAYAGG(JSON_OBJECT('key',n.node_key,'taskId',n.task_id,'pipelineId',n.pipeline_id))
   FROM processing_workflow_node n WHERE n.workflow_id=w.id),
  COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('from',e.from_key,'to',e.to_key))
   FROM processing_workflow_edge e WHERE e.workflow_id=w.id),JSON_ARRAY()),
  COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('key',n.node_key,'taskId',n.task_id,'ownerWorkflowId',t.workflow_id))
   FROM processing_workflow_node n JOIN sql_task t ON t.id=n.task_id
   WHERE n.workflow_id=w.id AND t.workflow_id<>w.id),JSON_ARRAY()),
  COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('taskId',d.task_id,'upstreamTaskId',d.upstream_id,'upstreamWorkflowId',u.workflow_id))
   FROM sql_task_dependency d JOIN sql_task t ON t.id=d.task_id JOIN sql_task u ON u.id=d.upstream_id
   WHERE t.workflow_id=w.id AND u.workflow_id<>w.id),JSON_ARRAY()),w.positions_json
 FROM processing_workflow w WHERE EXISTS(SELECT 1 FROM processing_workflow_node n
 JOIN sql_task t ON t.id=n.task_id WHERE n.workflow_id=w.id AND t.workflow_id<>w.id)
 OR EXISTS(SELECT 1 FROM sql_task_dependency d JOIN sql_task t ON t.id=d.task_id JOIN sql_task u ON u.id=d.upstream_id
 WHERE t.workflow_id=w.id AND u.workflow_id<>w.id);
UPDATE processing_workflow w JOIN workflow_sql_ownership_migration m ON m.workflow_id=w.id
 SET w.review_required=TRUE,w.version=w.version+1,
 w.ownership_notice='原跨流程 SQL 引用已移除，原拓扑已保留。请核对输入表及依赖；需要等待其他流程时请先人工确认其数据已就绪。核对并保存后方可运行。';
UPDATE workflow_legacy_binding b JOIN workflow_sql_ownership_migration m ON m.workflow_id=b.workflow_id SET b.graph_hash=NULL;
DELETE n FROM processing_workflow_node n JOIN sql_task t ON t.id=n.task_id WHERE n.workflow_id<>t.workflow_id;
-- Legacy root/hash workers for changed graphs are retired above. Keep no hidden cross-owner edit dependencies.
DELETE d FROM sql_task_dependency d JOIN sql_task t ON t.id=d.task_id JOIN sql_task u ON u.id=d.upstream_id
 WHERE t.workflow_id<>u.workflow_id;
UPDATE sql_task t JOIN processing_workflow w ON w.id=t.workflow_id SET t.archived=TRUE WHERE w.archived=TRUE;

ALTER TABLE processing_workflow DROP COLUMN legacy_sql_task_id;
ALTER TABLE sql_task MODIFY workflow_id BIGINT NOT NULL,
 ADD CONSTRAINT fk_sql_owner FOREIGN KEY(workflow_id) REFERENCES processing_workflow(id),
 ADD UNIQUE KEY uk_sql_owner_id(workflow_id,id);
ALTER TABLE processing_workflow_node DROP FOREIGN KEY fk_wf_node_task,
 ADD CONSTRAINT fk_wf_node_owned_task FOREIGN KEY(workflow_id,task_id) REFERENCES sql_task(workflow_id,id);

-- Archived definitions remain available to history but no longer reserve a live writer or name.
-- Keep a model index for fk_sql_model before removing its original unique index.
ALTER TABLE sql_task ADD INDEX ix_sql_model_history(model_id),
 ADD active_name VARCHAR(100) GENERATED ALWAYS AS (IF(archived=FALSE,name,NULL)) STORED,
 ADD active_model_id BIGINT GENERATED ALWAYS AS (IF(archived=FALSE,model_id,NULL)) STORED,
 ADD UNIQUE KEY uk_sql_active_name(active_name), ADD UNIQUE KEY uk_sql_active_model(active_model_id),
 DROP INDEX name, DROP INDEX model_id;
