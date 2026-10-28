-- Keep the original SQL and its replacement without rewriting execution history.
CREATE TABLE pipeline_transform_archive (
 pipeline_id BIGINT PRIMARY KEY,
 sql_text TEXT NOT NULL,
 task_id BIGINT NOT NULL,
 migrated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_pipeline_transform_source FOREIGN KEY(pipeline_id) REFERENCES pipeline(id),
 CONSTRAINT fk_pipeline_transform_task FOREIGN KEY(task_id) REFERENCES sql_task(id)
);
