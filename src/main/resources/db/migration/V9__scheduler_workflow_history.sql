CREATE TABLE scheduler_workflow_binding (
 resource_type VARCHAR(12) NOT NULL,
 resource_id BIGINT NOT NULL,
 definition_code BIGINT NOT NULL,
 registered_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(resource_type,resource_id,definition_code)
);

INSERT INTO scheduler_workflow_binding(resource_type,resource_id,definition_code)
SELECT 'pipeline',id,definition_code FROM pipeline WHERE definition_code IS NOT NULL;
INSERT INTO scheduler_workflow_binding(resource_type,resource_id,definition_code)
SELECT 'transform',id,definition_code FROM sql_task WHERE definition_code IS NOT NULL;
