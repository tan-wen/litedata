CREATE TABLE asset_profile (
 asset_type VARCHAR(16) NOT NULL,
 resource_id BIGINT NOT NULL,
 business_name VARCHAR(100) NOT NULL DEFAULT '',
 subject_area VARCHAR(100) NOT NULL DEFAULT '',
 owner VARCHAR(100) NOT NULL DEFAULT '',
 description VARCHAR(2000) NOT NULL DEFAULT '',
 tags VARCHAR(500) NOT NULL DEFAULT '',
 update_frequency VARCHAR(100) NOT NULL DEFAULT '',
 sensitivity VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
 version INT NOT NULL DEFAULT 1,
 updated_by VARCHAR(100) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(asset_type,resource_id),
 INDEX asset_profile_subject(subject_area),
 INDEX asset_profile_owner(owner)
);
