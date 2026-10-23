package com.litedata.model;

import java.time.LocalDateTime;

public class ModelExecution {
    private Long id;
    private long modelId;
    private int modelVersion;
    private String operation;
    private String targetTable;
    private String ddl;
    private String actor;
    private String status;
    private String message;
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getModelId() { return modelId; }
    public void setModelId(long modelId) { this.modelId = modelId; }
    public int getModelVersion() { return modelVersion; }
    public void setModelVersion(int modelVersion) { this.modelVersion = modelVersion; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getDdl() { return ddl; }
    public void setDdl(String ddl) { this.ddl = ddl; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
