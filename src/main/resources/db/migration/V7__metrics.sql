CREATE TABLE metric_graph_guard (id INT PRIMARY KEY);
INSERT INTO metric_graph_guard(id) VALUES(1);
CREATE TABLE metric_definition (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 code VARCHAR(64) NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL,
 type VARCHAR(16) NOT NULL,
 subject_area VARCHAR(100) NOT NULL,
 owner VARCHAR(100) NOT NULL,
 unit VARCHAR(30) NOT NULL DEFAULT '',
 description VARCHAR(1000) NOT NULL DEFAULT '',
 dimensions VARCHAR(1000) NOT NULL DEFAULT '',
 model_id BIGINT NULL,
 model_version INT NULL,
 aggregation VARCHAR(20) NOT NULL DEFAULT '',
 measure_column VARCHAR(64) NOT NULL DEFAULT '',
 time_column VARCHAR(64) NOT NULL DEFAULT '',
 base_metric_id BIGINT NULL,
 window_days INT NOT NULL DEFAULT 7,
 filter_expression VARCHAR(2000) NOT NULL DEFAULT '',
 formula VARCHAR(2000) NOT NULL DEFAULT '',
 version INT NOT NULL DEFAULT 1,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 sql_text MEDIUMTEXT NOT NULL,
 INDEX metric_catalog(archived,type,id),
 FOREIGN KEY(model_id) REFERENCES data_model(id),
 FOREIGN KEY(base_metric_id) REFERENCES metric_definition(id)
);
CREATE TABLE metric_version (
 metric_id BIGINT NOT NULL,
 version INT NOT NULL,
 actor VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 definition_json MEDIUMTEXT NOT NULL,
 sql_text MEDIUMTEXT NOT NULL,
 PRIMARY KEY(metric_id,version),
 FOREIGN KEY(metric_id) REFERENCES metric_definition(id)
);
CREATE TABLE metric_dependency (
 metric_id BIGINT NOT NULL,
 upstream_id BIGINT NOT NULL,
 upstream_version INT NOT NULL,
 PRIMARY KEY(metric_id,upstream_id),
 FOREIGN KEY(metric_id) REFERENCES metric_definition(id),
 FOREIGN KEY(upstream_id,upstream_version) REFERENCES metric_version(metric_id,version)
);
