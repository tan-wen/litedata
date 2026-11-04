-- Keep operational history while hiding retired test and draft resources from active views.
ALTER TABLE data_source ADD archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pipeline ADD archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE data_service ADD archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX ix_data_source_archived_id ON data_source(archived,id);
CREATE INDEX ix_pipeline_archived_id ON pipeline(archived,id);
CREATE INDEX ix_data_service_archived_id ON data_service(archived,id);
