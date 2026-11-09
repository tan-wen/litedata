CREATE TABLE data_dictionary (
 code VARCHAR(50) PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '',
 version INT NOT NULL DEFAULT 1
);
CREATE TABLE data_dictionary_item (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 dictionary_code VARCHAR(50) NOT NULL,
 value VARCHAR(100) NOT NULL,
 label VARCHAR(100) NOT NULL,
 description VARCHAR(1000) NOT NULL DEFAULT '',
 sort_order INT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INT NOT NULL DEFAULT 1,
 UNIQUE KEY dictionary_value(dictionary_code,value),
 INDEX dictionary_order(dictionary_code,sort_order,id),
 FOREIGN KEY(dictionary_code) REFERENCES data_dictionary(code)
);
INSERT INTO data_dictionary(code,name,description) VALUES('SUBJECT_AREA','主题域','模型、指标、资产和数据服务共用的业务主题域，可按所属行业维护。');
INSERT INTO data_dictionary_item(dictionary_code,value,label,description) VALUES('SUBJECT_AREA','贴源接入','贴源接入','自动生成 ODS 模型时使用，可按行业调整显示名称。');

ALTER TABLE data_service ADD COLUMN subject_area VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN description VARCHAR(2000) NOT NULL DEFAULT '';
UPDATE data_service s JOIN asset_profile p ON p.asset_type='SERVICE' AND p.resource_id=s.id
 SET s.subject_area=p.subject_area,s.description=p.description;
UPDATE asset_profile SET subject_area='',description='' WHERE asset_type='SERVICE';

INSERT IGNORE INTO data_dictionary_item(dictionary_code,value,label)
 SELECT 'SUBJECT_AREA',area,area FROM (
   SELECT subject_area area FROM data_model
   UNION SELECT subject_area FROM metric_definition
   UNION SELECT subject_area FROM asset_profile
   UNION SELECT subject_area FROM data_service
 ) existing_areas WHERE area<>'';
