ALTER TABLE pipeline_run
 ADD COLUMN workflow_instance_id BIGINT NULL,
 ADD COLUMN task_instance_id BIGINT NULL,
 ADD INDEX ix_pipeline_run_instance (pipeline_id,workflow_instance_id,started_at,id);
