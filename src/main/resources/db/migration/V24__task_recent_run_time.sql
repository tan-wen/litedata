-- Keep list queries local and indexed. The definition is verified against the
-- scheduler before a pipeline run is claimed; never infer ownership by time.
ALTER TABLE pipeline_run
 ADD definition_code BIGINT NULL,
 ADD INDEX ix_pipeline_run_definition_time(definition_code,started_at);
CREATE INDEX ix_sql_run_workflow_time ON sql_task_run(workflow_id,started_at);
