package com.litedata.model;

public class SqlTask {
    private Long id;
    private String name;
    private Long modelId;
    private String targetTable;
    private String modelName;
    private String layer;
    private String sqlText;
    private String cronExpression = "0 0 * * * ?";
    private int version;
    private String status;
    private Long definitionCode;
    private String definitionHash;
    private Integer scheduleId;
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
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDefinitionCode() { return definitionCode; }
    public void setDefinitionCode(Long definitionCode) { this.definitionCode = definitionCode; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String definitionHash) { this.definitionHash = definitionHash; }
    public Integer getScheduleId() { return scheduleId; }
    public void setScheduleId(Integer scheduleId) { this.scheduleId = scheduleId; }
    public Long getActiveRunId() { return activeRunId; }
    public void setActiveRunId(Long activeRunId) { this.activeRunId = activeRunId; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public java.util.List<Long> getUpstreamTaskIds() { return upstreamTaskIds; }
    public void setUpstreamTaskIds(java.util.List<Long> upstreamTaskIds) { this.upstreamTaskIds = upstreamTaskIds; }
    public java.util.List<Long> getUpstreamPipelineIds() { return upstreamPipelineIds; }
    public void setUpstreamPipelineIds(java.util.List<Long> upstreamPipelineIds) { this.upstreamPipelineIds = upstreamPipelineIds; }
}
