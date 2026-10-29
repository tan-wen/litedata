package com.litedata.model;

public class Pipeline {
    private Long id;
    private String name;
    private Long sourceId;
    private String sourceTable;
    private String watermarkColumn;
    private String targetTable;
    private String transformSql;
    private String cronExpression;
    private String status;
    private String lastError;
    private java.time.LocalDateTime watermark;
    private Long definitionCode;
    private Integer scheduleId;
    private Long activeRunId;
    private String sourceSchemaHash;
    private String targetSchemaHash;
    private String sourceKeyColumns;
    public String getSourceSchemaHash() { return sourceSchemaHash; }
    public void setSourceSchemaHash(String value) { sourceSchemaHash=value; }
    public String getTargetSchemaHash() { return targetSchemaHash; }
    public void setTargetSchemaHash(String value) { targetSchemaHash=value; }
    public String getSourceKeyColumns() { return sourceKeyColumns; }
    public void setSourceKeyColumns(String value) { sourceKeyColumns=value; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String sourceTable) { this.sourceTable = sourceTable; }
    public String getWatermarkColumn() { return watermarkColumn; }
    public void setWatermarkColumn(String watermarkColumn) { this.watermarkColumn = watermarkColumn; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getTransformSql() { return transformSql; }
    public void setTransformSql(String transformSql) { this.transformSql = transformSql; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public java.time.LocalDateTime getWatermark() { return watermark; }
    public void setWatermark(java.time.LocalDateTime watermark) { this.watermark = watermark; }
    public Long getDefinitionCode() { return definitionCode; }
    public void setDefinitionCode(Long definitionCode) { this.definitionCode = definitionCode; }
    public Integer getScheduleId() { return scheduleId; }
    public void setScheduleId(Integer scheduleId) { this.scheduleId = scheduleId; }
    public Long getActiveRunId() { return activeRunId; }
    public void setActiveRunId(Long activeRunId) { this.activeRunId = activeRunId; }
}

