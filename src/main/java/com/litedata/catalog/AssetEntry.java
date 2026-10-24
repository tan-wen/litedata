package com.litedata.catalog;

import java.time.*;

/** Public catalog projection. Never contains SQL, connection details, or credentials. */
public class AssetEntry {
    private AssetType type;
    private Long resourceId,metadataId,collectedEpoch;
    private String name,code,subjectArea,owner,description,layer,sourceName,status,tags,updateFrequency,sensitivity,scopeKey,tableName;
    private int resourceVersion;
    public AssetType getType() { return type; }
    public void setType(AssetType value) { type=value; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long value) { resourceId=value; }
    public Long getMetadataId() { return metadataId; }
    public void setMetadataId(Long value) { metadataId=value; }
    public String getName() { return name; }
    public void setName(String value) { name=value; }
    public String getCode() { return code; }
    public void setCode(String value) { code=value; }
    public String getSubjectArea() { return subjectArea; }
    public void setSubjectArea(String value) { subjectArea=value; }
    public String getOwner() { return owner; }
    public void setOwner(String value) { owner=value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description=value; }
    public String getLayer() { return layer; }
    public void setLayer(String value) { layer=value; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String value) { sourceName=value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    public String getStatusLabel() { return AssetCatalogService.STATUSES.getOrDefault(status,status); }
    public String getTags() { return tags; }
    public void setTags(String value) { tags=value; }
    public String getUpdateFrequency() { return updateFrequency; }
    public void setUpdateFrequency(String value) { updateFrequency=value; }
    public String getSensitivity() { return sensitivity; }
    public void setSensitivity(String value) { sensitivity=value; }
    public String getSensitivityLabel() { return AssetCatalogService.SENSITIVITIES.getOrDefault(sensitivity,sensitivity); }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String value) { scopeKey=value; }
    public String getTableName() { return tableName; }
    public void setTableName(String value) { tableName=value; }
    public int getResourceVersion() { return resourceVersion; }
    public void setResourceVersion(int value) { resourceVersion=value; }
    public Long getCollectedEpoch() { return collectedEpoch; }
    public void setCollectedEpoch(Long value) { collectedEpoch=value; }
    public LocalDateTime getCollectedAt() { return collectedEpoch==null?null:LocalDateTime.ofInstant(Instant.ofEpochSecond(collectedEpoch),ZoneId.of("Asia/Shanghai")); }
    public String getPath() { return "/assets/"+type.name()+"/"+resourceId; }
    public String getResourcePath() {
        return switch(type) {
            case TABLE -> "/metadata/"+resourceId;
            case MODEL -> "ARCHIVED".equals(status)?null:"/models/"+resourceId;
            case METRIC -> "/metrics/"+resourceId+("ARCHIVED".equals(status)?"/versions/"+resourceVersion:"");
            case SERVICE -> "/services/"+resourceId+"/calls";
        };
    }
    public String getLineagePath() {
        if(type==AssetType.METRIC) return getResourcePath()+"/lineage";
        return metadataId==null?null:"/metadata/"+metadataId+"/lineage";
    }
}
