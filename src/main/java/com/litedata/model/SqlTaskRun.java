package com.litedata.model;

public class SqlTaskRun {
    private Long id;
    private long taskId;
    private long rootTaskId;
    private int taskVersion;
    private String targetTable;
    private String sqlText;
    private String status;
    private String message;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private String taskName;
    private String rootName;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getTaskId() { return taskId; }
    public void setTaskId(long taskId) { this.taskId = taskId; }
    public long getRootTaskId() { return rootTaskId; }
    public void setRootTaskId(long rootTaskId) { this.rootTaskId = rootTaskId; }
    public int getTaskVersion() { return taskVersion; }
    public void setTaskVersion(int taskVersion) { this.taskVersion = taskVersion; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public java.time.LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(java.time.LocalDateTime startedAt) { this.startedAt = startedAt; }
    public java.time.LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(java.time.LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getRootName() { return rootName; }
    public void setRootName(String rootName) { this.rootName = rootName; }
}
