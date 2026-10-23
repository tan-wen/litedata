CREATE TABLE sql_graph_guard (id INT PRIMARY KEY);
INSERT INTO sql_graph_guard(id) VALUES(1);
CREATE TABLE sql_task (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL UNIQUE,
 model_id BIGINT NOT NULL UNIQUE, sql_text TEXT NOT NULL,
 cron_expression VARCHAR(100) NOT NULL DEFAULT '0 0 * * * ?',
 version INT NOT NULL DEFAULT 1, status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
 definition_code BIGINT, definition_hash CHAR(64), schedule_id INT, active_run_id BIGINT,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_sql_model FOREIGN KEY(model_id) REFERENCES data_model(id)
);
CREATE TABLE sql_task_dependency (
 task_id BIGINT NOT NULL, upstream_id BIGINT NOT NULL,
 PRIMARY KEY(task_id,upstream_id),
 CONSTRAINT fk_sql_dep_task FOREIGN KEY(task_id) REFERENCES sql_task(id),
 CONSTRAINT fk_sql_dep_upstream FOREIGN KEY(upstream_id) REFERENCES sql_task(id)
);
CREATE TABLE sql_pipeline_dependency (
 task_id BIGINT NOT NULL, pipeline_id BIGINT NOT NULL,
 PRIMARY KEY(task_id,pipeline_id),
 CONSTRAINT fk_sql_pipe_task FOREIGN KEY(task_id) REFERENCES sql_task(id),
 CONSTRAINT fk_sql_pipe_source FOREIGN KEY(pipeline_id) REFERENCES pipeline(id)
);
CREATE TABLE sql_task_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL, root_task_id BIGINT NOT NULL,
 task_version INT NOT NULL, target_table VARCHAR(64) NOT NULL, sql_text TEXT NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'RUNNING', message VARCHAR(500),
 started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at TIMESTAMP(6),
 CONSTRAINT fk_sql_run_task FOREIGN KEY(task_id) REFERENCES sql_task(id),
 CONSTRAINT fk_sql_run_root FOREIGN KEY(root_task_id) REFERENCES sql_task(id),
 INDEX ix_sql_run_root(root_task_id,id)
);
