-- Workflow execution definitions and their engine history are retained after removal.
-- Archived workflows are excluded from all operational and dependency queries.
ALTER TABLE processing_workflow
 ADD archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX ix_processing_workflow_archived_id ON processing_workflow(archived,id);
