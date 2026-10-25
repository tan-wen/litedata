CREATE TABLE user_api_key (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE,
 token_prefix VARCHAR(20) NOT NULL,
 scopes VARCHAR(200) NOT NULL,
 service_slugs TEXT NOT NULL,
 pipeline_ids TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 expires_at DATETIME(6) NOT NULL,
 last_used_at DATETIME(6),
 revoked_at DATETIME(6),
 CONSTRAINT fk_user_api_key_owner FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
 INDEX ix_user_api_key_owner (user_id,id)
);

CREATE TABLE user_api_key_service (
 key_id BIGINT NOT NULL,
 service_id BIGINT NOT NULL,
 PRIMARY KEY (key_id,service_id),
 CONSTRAINT fk_key_service_key FOREIGN KEY (key_id) REFERENCES user_api_key(id) ON DELETE CASCADE,
 CONSTRAINT fk_key_service_resource FOREIGN KEY (service_id) REFERENCES data_service(id) ON DELETE CASCADE
);

CREATE TABLE user_api_key_pipeline (
 key_id BIGINT NOT NULL,
 pipeline_id BIGINT NOT NULL,
 PRIMARY KEY (key_id,pipeline_id),
 CONSTRAINT fk_key_pipeline_key FOREIGN KEY (key_id) REFERENCES user_api_key(id) ON DELETE CASCADE,
 CONSTRAINT fk_key_pipeline_resource FOREIGN KEY (pipeline_id) REFERENCES pipeline(id) ON DELETE CASCADE
);
