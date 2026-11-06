ALTER TABLE pipeline ADD COLUMN quality_task_id BIGINT NULL,
 ADD CONSTRAINT fk_pipeline_quality FOREIGN KEY (quality_task_id) REFERENCES quality_task(id);
ALTER TABLE pipeline_run ADD COLUMN quality_task_id BIGINT NULL,
 ADD COLUMN quality_task_version INT NULL,
 ADD COLUMN quality_run_id BIGINT NULL,
 ADD COLUMN quality_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
 ADD INDEX pipeline_quality_pending(quality_status,id);
ALTER TABLE quality_run ADD COLUMN pipeline_run_id BIGINT NULL,
 ADD UNIQUE KEY quality_pipeline_run(pipeline_run_id),
 ADD CONSTRAINT fk_quality_pipeline_run FOREIGN KEY(pipeline_run_id) REFERENCES pipeline_run(id);
ALTER TABLE processing_workflow ADD COLUMN trigger_mode VARCHAR(10) NOT NULL DEFAULT 'CRON',
 ADD COLUMN trigger_pipeline_id BIGINT NULL,
 ADD COLUMN trigger_generation INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT fk_workflow_trigger_pipeline FOREIGN KEY(trigger_pipeline_id) REFERENCES pipeline(id),
 ADD INDEX workflow_event_subscription(trigger_pipeline_id,trigger_mode,status,archived);

CREATE TABLE data_ready_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 pipeline_id BIGINT NOT NULL,
 pipeline_run_id BIGINT NOT NULL UNIQUE,
 quality_run_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(pipeline_id) REFERENCES pipeline(id),
 FOREIGN KEY(pipeline_run_id) REFERENCES pipeline_run(id),
 FOREIGN KEY(quality_run_id) REFERENCES quality_run(id)
);
CREATE TABLE data_ready_delivery (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 event_id BIGINT NOT NULL,
 workflow_id BIGINT NOT NULL,
 trigger_generation INT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 definition_code BIGINT NULL,
 message VARCHAR(500) NOT NULL DEFAULT '',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 submitted_at DATETIME(6) NULL,
 UNIQUE KEY ready_delivery_once(event_id,workflow_id),
 INDEX ready_delivery_pending(state,id),
 INDEX ready_delivery_workflow(workflow_id,id),
 FOREIGN KEY(event_id) REFERENCES data_ready_event(id),
 FOREIGN KEY(workflow_id) REFERENCES processing_workflow(id)
);
CREATE TABLE quality_alert (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 quality_run_id BIGINT NOT NULL UNIQUE,
 severity VARCHAR(20) NOT NULL,
 message VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(quality_run_id) REFERENCES quality_run(id)
);
