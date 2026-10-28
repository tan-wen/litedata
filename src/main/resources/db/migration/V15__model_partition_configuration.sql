ALTER TABLE data_model
 ADD COLUMN partition_range_style VARCHAR(12) NOT NULL DEFAULT 'FIXED',
 ADD COLUMN dynamic_partition_properties TEXT NULL;

CREATE TABLE model_partition (
 model_id BIGINT NOT NULL,
 ordinal_position INT NOT NULL,
 name VARCHAR(64) NOT NULL,
 lower_bound VARCHAR(256) NOT NULL DEFAULT '',
 upper_bound VARCHAR(256) NOT NULL DEFAULT '',
 values_text TEXT NOT NULL,
 PRIMARY KEY(model_id, ordinal_position), UNIQUE(model_id, name),
 CONSTRAINT fk_partition_model FOREIGN KEY(model_id) REFERENCES data_model(id) ON DELETE CASCADE
);
