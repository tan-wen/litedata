CREATE TABLE metadata_scope (
 scope_key VARCHAR(40) PRIMARY KEY
);
CREATE TABLE metadata_table (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 scope_key VARCHAR(40) NOT NULL,
 catalog_name VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 table_name VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 comment TEXT NOT NULL,
 schema_hash CHAR(64) NOT NULL,
 version INT NOT NULL DEFAULT 1,
 present BOOLEAN NOT NULL DEFAULT TRUE,
 collected_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(scope_key,catalog_name,table_name),
 CONSTRAINT fk_metadata_scope FOREIGN KEY(scope_key) REFERENCES metadata_scope(scope_key)
);
CREATE TABLE metadata_column (
 table_id BIGINT NOT NULL, ordinal_position INT NOT NULL,
 name VARCHAR(64) NOT NULL, data_type TEXT NOT NULL,
 nullable BOOLEAN NOT NULL, key_column BOOLEAN NOT NULL, comment TEXT NOT NULL,
 PRIMARY KEY(table_id,ordinal_position),
 CONSTRAINT fk_metadata_column FOREIGN KEY(table_id) REFERENCES metadata_table(id)
);
