package com.litedata.quality;

public class QualityRun {
    public String getStatusLabel() { return statusLabel(status); }
    static String statusLabel(String status) { return switch(java.util.Objects.toString(status,"")) {
        case "PASSED" -> "通过"; case "WARNING" -> "警告"; case "FAILED" -> "异常"; case "ERROR" -> "执行失败"; case "RUNNING" -> "扫描中"; default -> "—";
    }; }
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    private Long taskId;
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { this.taskId = value; }
    private int taskVersion;
    public int getTaskVersion() { return taskVersion; }
    public void setTaskVersion(int value) { this.taskVersion = value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    private String actor;
    public String getActor() { return actor; }
    public void setActor(String value) { this.actor = value; }
    private String message;
    public String getMessage() { return message; }
    public void setMessage(String value) { this.message = value; }
    private String configSnapshot;
    public String getConfigSnapshot() { return configSnapshot; }
    public void setConfigSnapshot(String value) { this.configSnapshot = value; }
    private java.time.LocalDateTime startedAt;
    public java.time.LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(java.time.LocalDateTime value) { this.startedAt = value; }
    private java.time.LocalDateTime finishedAt;
    public java.time.LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(java.time.LocalDateTime value) { this.finishedAt = value; }
}
