package com.litedata.model;

public class SqlTask {
    private Long id;
    private String name;
    private Long modelId;
    private String targetTable;
    private String modelName;
    private String layer;
    private String sqlText;
    private int version;
    private Long activeRunId;
    private boolean archived;
    private java.util.List<Long> upstreamTaskIds = new java.util.ArrayList<>();
    private java.util.List<Long> upstreamPipelineIds = new java.util.ArrayList<>();
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Long getActiveRunId() { return activeRunId; }
    public void setActiveRunId(Long activeRunId) { this.activeRunId = activeRunId; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public java.util.List<Long> getUpstreamTaskIds() { return upstreamTaskIds; }
    public void setUpstreamTaskIds(java.util.List<Long> upstreamTaskIds) { this.upstreamTaskIds = upstreamTaskIds; }
    public java.util.List<Long> getUpstreamPipelineIds() { return upstreamPipelineIds; }
    public void setUpstreamPipelineIds(java.util.List<Long> upstreamPipelineIds) { this.upstreamPipelineIds = upstreamPipelineIds; }
}
