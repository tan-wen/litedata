package com.litedata.quality;

public class QualityTask {
    private int ruleCount;
    public int getRuleCount() { return ruleCount; }
    public void setRuleCount(int value) { this.ruleCount = value; }
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    private String name;
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    private Long sourceId;
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long value) { this.sourceId = value; }
    private String tableName;
    public String getTableName() { return tableName; }
    public void setTableName(String value) { this.tableName = value; }
    private String owner;
    public String getOwner() { return owner; }
    public void setOwner(String value) { this.owner = value; }
    private String description;
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value; }
    private String cronExpression;
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String value) { this.cronExpression = value; }
    private String timeColumn;
    public String getTimeColumn() { return timeColumn; }
    public void setTimeColumn(String value) { this.timeColumn = value; }
    private int windowHours = 24;
    public int getWindowHours() { return windowHours; }
    public void setWindowHours(int value) { this.windowHours = value; }
    private int timeoutSeconds = 30;
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { this.timeoutSeconds = value; }
    private int version = 1;
    public int getVersion() { return version; }
    public void setVersion(int value) { this.version = value; }
    private boolean enabled;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { this.enabled = value; }
    private boolean archived;
    public boolean isArchived() { return archived; }
    public void setArchived(boolean value) { this.archived = value; }
    private Long activeRunId;
    public Long getActiveRunId() { return activeRunId; }
    public void setActiveRunId(Long value) { this.activeRunId = value; }
    private java.time.LocalDateTime nextRunAt;
    public java.time.LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(java.time.LocalDateTime value) { this.nextRunAt = value; }
    private java.util.List<QualityRule> rules = new java.util.ArrayList<>();
    public java.util.List<QualityRule> getRules() { return rules; }
    public void setRules(java.util.List<QualityRule> value) { this.rules = value; }
}
