-- NULL preserves existing pipelines; automatically generated ODS pipelines pin their schema.
ALTER TABLE pipeline
 ADD COLUMN source_schema_hash CHAR(64),
 ADD COLUMN target_schema_hash CHAR(64),
 ADD COLUMN source_key_columns TEXT;
