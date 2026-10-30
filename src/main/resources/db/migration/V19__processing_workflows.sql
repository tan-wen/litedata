CREATE TABLE processing_workflow (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL UNIQUE,
 description VARCHAR(500) NOT NULL DEFAULT '',
 root_task_id BIGINT NOT NULL UNIQUE,
 version INT NOT NULL DEFAULT 1,
 positions_json TEXT,
 CONSTRAINT fk_workflow_root FOREIGN KEY(root_task_id) REFERENCES sql_task(id) ON DELETE CASCADE
);

-- Preserve existing schedules and expose existing terminal chains without changing execution.
INSERT INTO processing_workflow(name,root_task_id)
SELECT t.name,t.id FROM sql_task t WHERE t.archived=FALSE AND (
 t.schedule_id IS NOT NULL OR NOT EXISTS (
  SELECT 1 FROM sql_task_dependency d JOIN sql_task downstream ON downstream.id=d.task_id
  WHERE d.upstream_id=t.id AND downstream.archived=FALSE
 ));

CREATE TABLE processing_workflow_snapshot (
 definition_code BIGINT NOT NULL,
 root_task_id BIGINT NOT NULL,
 nodes_json MEDIUMTEXT NOT NULL,
 PRIMARY KEY(definition_code,root_task_id),
 CONSTRAINT fk_workflow_snapshot_root FOREIGN KEY(root_task_id) REFERENCES sql_task(id) ON DELETE CASCADE
);
