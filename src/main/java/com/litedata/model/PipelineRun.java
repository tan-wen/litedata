package com.litedata.model;

public class PipelineRun {
    private Long id;
    private Long pipelineId;
    private String pipelineName;
    private String status;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private java.time.LocalDateTime lowerBound;
    private java.time.LocalDateTime upperBound;
    private String message;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPipelineId() { return pipelineId; }
    public void setPipelineId(Long pipelineId) { this.pipelineId = pipelineId; }
    public String getPipelineName() { return pipelineName; }
    public void setPipelineName(String pipelineName) { this.pipelineName = pipelineName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public java.time.LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(java.time.LocalDateTime startedAt) { this.startedAt = startedAt; }
    public java.time.LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(java.time.LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public java.time.LocalDateTime getLowerBound() { return lowerBound; }
    public void setLowerBound(java.time.LocalDateTime lowerBound) { this.lowerBound = lowerBound; }
    public java.time.LocalDateTime getUpperBound() { return upperBound; }
    public void setUpperBound(java.time.LocalDateTime upperBound) { this.upperBound = upperBound; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}

