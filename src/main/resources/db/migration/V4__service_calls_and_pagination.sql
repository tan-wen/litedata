ALTER TABLE data_service DROP COLUMN max_rows;

CREATE TABLE service_call (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 service_id BIGINT NOT NULL,
 started_at DATETIME(6) NOT NULL COMMENT 'UTC request start time',
 duration_ms BIGINT NOT NULL,
 status_code INT NOT NULL,
 row_count INT NOT NULL DEFAULT 0,
 page_num INT,
 page_size INT,
 CONSTRAINT fk_call_service FOREIGN KEY (service_id) REFERENCES data_service(id),
 INDEX ix_call_service_time (service_id, started_at, id)
);
