package com.litedata.quality;

public class QualityResult {
    public String getStatusLabel() { return QualityRun.statusLabel(status); }
    private java.math.BigDecimal metricValue;
    public java.math.BigDecimal getMetricValue() { return metricValue; }
    public void setMetricValue(java.math.BigDecimal value) { this.metricValue = value; }
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    private Long runId;
    public Long getRunId() { return runId; }
    public void setRunId(Long value) { this.runId = value; }
    private String name;
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    private RuleType type;
    public RuleType getType() { return type; }
    public void setType(RuleType value) { this.type = value; }
    private String severity;
    public String getSeverity() { return severity; }
    public void setSeverity(String value) { this.severity = value; }
    private long totalCount;
    public long getTotalCount() { return totalCount; }
    public void setTotalCount(long value) { this.totalCount = value; }
    private long anomalyCount;
    public long getAnomalyCount() { return anomalyCount; }
    public void setAnomalyCount(long value) { this.anomalyCount = value; }
    private java.math.BigDecimal anomalyPercent;
    public java.math.BigDecimal getAnomalyPercent() { return anomalyPercent; }
    public void setAnomalyPercent(java.math.BigDecimal value) { this.anomalyPercent = value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    private String message;
    public String getMessage() { return message; }
    public void setMessage(String value) { this.message = value; }
}
