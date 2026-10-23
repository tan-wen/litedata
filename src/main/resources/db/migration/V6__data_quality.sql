CREATE TABLE quality_task (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL UNIQUE,
 source_id BIGINT NULL,
 table_name VARCHAR(64) NOT NULL,
 owner VARCHAR(100) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '',
 cron_expression VARCHAR(100) NOT NULL DEFAULT '',
 time_column VARCHAR(64) NOT NULL DEFAULT '',
 window_hours INT NOT NULL DEFAULT 24,
 timeout_seconds INT NOT NULL DEFAULT 30,
 version INT NOT NULL DEFAULT 1,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 active_run_id BIGINT NULL,
 next_run_at DATETIME(6) NULL,
 INDEX quality_due(enabled,archived,next_run_at),
 FOREIGN KEY(source_id) REFERENCES data_source(id)
);
CREATE TABLE quality_rule (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 type VARCHAR(40) NOT NULL,
 columns VARCHAR(1000) NOT NULL,
 parameter1 TEXT NOT NULL,
 parameter2 TEXT NOT NULL,
 reference_table VARCHAR(64) NOT NULL,
 reference_column VARCHAR(64) NOT NULL,
 tolerance_count BIGINT NOT NULL DEFAULT 0,
 tolerance_percent DECIMAL(9,4) NOT NULL DEFAULT 0,
 severity VARCHAR(10) NOT NULL,
 FOREIGN KEY(task_id) REFERENCES quality_task(id)
);
CREATE TABLE quality_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id BIGINT NOT NULL,
 task_version INT NOT NULL,
 status VARCHAR(16) NOT NULL,
 actor VARCHAR(100) NOT NULL,
 message VARCHAR(500) NOT NULL DEFAULT '',
 config_snapshot MEDIUMTEXT NOT NULL,
 started_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 INDEX quality_run_task(task_id,id),
 FOREIGN KEY(task_id) REFERENCES quality_task(id)
);
CREATE TABLE quality_result (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 run_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 type VARCHAR(40) NOT NULL,
 severity VARCHAR(10) NOT NULL,
 total_count BIGINT NOT NULL,
 anomaly_count BIGINT NOT NULL,
 anomaly_percent DECIMAL(9,4) NOT NULL,
 status VARCHAR(16) NOT NULL,
 message VARCHAR(500) NOT NULL,
 metric_value DECIMAL(38,8) NULL,
 FOREIGN KEY(run_id) REFERENCES quality_run(id),
 INDEX quality_result_run(run_id,id)
);
