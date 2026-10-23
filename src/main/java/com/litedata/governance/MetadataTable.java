package com.litedata.governance;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;

public class MetadataTable {
    private Long id;
    private String scopeKey, catalogName, tableName, comment, schemaHash, sourceName, layer, modelName;
    private Long modelId;
    private int version;
    private boolean present;
    private LocalDateTime collectedAt;
    private Long collectedEpoch;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String value) { scopeKey=value; }
    public String getCatalogName() { return catalogName; }
    public void setCatalogName(String value) { catalogName=value; }
    public String getTableName() { return tableName; }
    public void setTableName(String value) { tableName=value; }
    public String getComment() { return comment; }
    public void setComment(String value) { comment=value; }
    public String getSchemaHash() { return schemaHash; }
    public void setSchemaHash(String value) { schemaHash=value; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String value) { sourceName=value; }
    public String getLayer() { return layer; }
    public void setLayer(String value) { layer=value; }
    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName=value; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long value) { modelId=value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { version=value; }
    public boolean isPresent() { return present; }
    public void setPresent(boolean value) { present=value; }
    public LocalDateTime getCollectedAt() { return collectedEpoch==null?collectedAt:LocalDateTime.ofInstant(Instant.ofEpochSecond(collectedEpoch),ZoneId.of("Asia/Shanghai")); }
    public void setCollectedAt(LocalDateTime value) { collectedAt=value; }
    public Long getCollectedEpoch() { return collectedEpoch; }
    public void setCollectedEpoch(Long value) { collectedEpoch=value; }
}
