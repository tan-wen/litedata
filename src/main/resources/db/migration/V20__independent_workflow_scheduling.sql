-- Move scheduling ownership from a terminal SQL task to the workflow itself.
-- SQL scheduling endpoints could have been used after V19's one-time backfill.
INSERT INTO processing_workflow(name,root_task_id)
 SELECT CONCAT('迁移调度-',t.id,'-',LEFT(UUID(),8)),t.id FROM sql_task t
 WHERE t.schedule_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM processing_workflow w WHERE w.root_task_id=t.id);
ALTER TABLE processing_workflow
 ADD cron_expression VARCHAR(100) NOT NULL DEFAULT '0 0 * * * ?',
 ADD status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
 ADD definition_code BIGINT,
 ADD definition_hash CHAR(64),
 ADD schedule_id INT;
UPDATE processing_workflow w JOIN sql_task t ON t.id=w.root_task_id
 SET w.cron_expression=t.cron_expression,w.status=t.status,w.definition_code=t.definition_code,
     w.definition_hash=t.definition_hash,w.schedule_id=t.schedule_id;

CREATE TABLE processing_workflow_node (
 workflow_id BIGINT NOT NULL, node_key VARCHAR(40) NOT NULL,
 task_id BIGINT, pipeline_id BIGINT,
 PRIMARY KEY(workflow_id,node_key),
 CONSTRAINT fk_wf_node_workflow FOREIGN KEY(workflow_id) REFERENCES processing_workflow(id) ON DELETE CASCADE,
 CONSTRAINT fk_wf_node_task FOREIGN KEY(task_id) REFERENCES sql_task(id),
 CONSTRAINT fk_wf_node_pipeline FOREIGN KEY(pipeline_id) REFERENCES pipeline(id),
 CONSTRAINT ck_wf_node_ref CHECK ((task_id IS NOT NULL AND pipeline_id IS NULL) OR (task_id IS NULL AND pipeline_id IS NOT NULL))
);
INSERT INTO processing_workflow_node(workflow_id,node_key,task_id)
WITH RECURSIVE ancestors(workflow_id,task_id) AS (
 SELECT id,root_task_id FROM processing_workflow
 UNION DISTINCT
 SELECT a.workflow_id,d.upstream_id FROM ancestors a JOIN sql_task_dependency d ON d.task_id=a.task_id
)
SELECT workflow_id,CONCAT('sql-',task_id),task_id FROM ancestors;
INSERT INTO processing_workflow_node(workflow_id,node_key,pipeline_id)
SELECT DISTINCT n.workflow_id,CONCAT('pipeline-',d.pipeline_id),d.pipeline_id
 FROM processing_workflow_node n JOIN sql_pipeline_dependency d ON d.task_id=n.task_id;
CREATE TABLE processing_workflow_edge (
 workflow_id BIGINT NOT NULL, from_key VARCHAR(40) NOT NULL, to_key VARCHAR(40) NOT NULL,
 PRIMARY KEY(workflow_id,from_key,to_key),
 CONSTRAINT fk_wf_edge_from FOREIGN KEY(workflow_id,from_key) REFERENCES processing_workflow_node(workflow_id,node_key) ON DELETE CASCADE,
 CONSTRAINT fk_wf_edge_to FOREIGN KEY(workflow_id,to_key) REFERENCES processing_workflow_node(workflow_id,node_key) ON DELETE CASCADE
);
INSERT INTO processing_workflow_edge(workflow_id,from_key,to_key)
SELECT n.workflow_id,CONCAT('sql-',d.upstream_id),n.node_key
 FROM processing_workflow_node n JOIN sql_task_dependency d ON d.task_id=n.task_id
UNION ALL
SELECT n.workflow_id,CONCAT('pipeline-',d.pipeline_id),n.node_key
 FROM processing_workflow_node n JOIN sql_pipeline_dependency d ON d.task_id=n.task_id;

CREATE TABLE workflow_definition_snapshot (
 workflow_id BIGINT NOT NULL, definition_code BIGINT NOT NULL, nodes_json MEDIUMTEXT NOT NULL,
 PRIMARY KEY(workflow_id,definition_code),
 CONSTRAINT fk_wf_snapshot FOREIGN KEY(workflow_id) REFERENCES processing_workflow(id) ON DELETE CASCADE
);
INSERT INTO workflow_definition_snapshot(workflow_id,definition_code,nodes_json)
 SELECT w.id,s.definition_code,s.nodes_json FROM processing_workflow_snapshot s
 JOIN processing_workflow w ON w.root_task_id=s.root_task_id;
CREATE TABLE workflow_legacy_binding (
 workflow_id BIGINT PRIMARY KEY, root_task_id BIGINT NOT NULL UNIQUE, graph_hash CHAR(64),
 CONSTRAINT fk_wf_legacy FOREIGN KEY(workflow_id) REFERENCES processing_workflow(id) ON DELETE CASCADE
);
INSERT INTO workflow_legacy_binding(workflow_id,root_task_id,graph_hash)
 SELECT w.id,w.root_task_id,t.definition_hash FROM processing_workflow w JOIN sql_task t ON t.id=w.root_task_id;
ALTER TABLE sql_task_run ADD workflow_id BIGINT,
 MODIFY root_task_id BIGINT NULL,
 ADD CONSTRAINT fk_sql_run_workflow FOREIGN KEY(workflow_id) REFERENCES processing_workflow(id),
 ADD INDEX ix_sql_run_workflow(workflow_id,id);
UPDATE sql_task_run r JOIN processing_workflow w ON w.root_task_id=r.root_task_id SET r.workflow_id=w.id;

-- Retain the legacy dependency/snapshot tables for already-published worker definitions and historical logs.
ALTER TABLE processing_workflow DROP FOREIGN KEY fk_workflow_root, DROP COLUMN root_task_id;
ALTER TABLE sql_task DROP COLUMN cron_expression, DROP COLUMN status, DROP COLUMN definition_code,
 DROP COLUMN definition_hash, DROP COLUMN schedule_id;
