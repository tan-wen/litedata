package com.litedata.quality;

public class QualityRule {
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    private Long taskId;
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { this.taskId = value; }
    private String name;
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    private RuleType type = RuleType.NOT_NULL;
    public RuleType getType() { return type; }
    public void setType(RuleType value) { this.type = value; }
    private String columns;
    public String getColumns() { return columns; }
    public void setColumns(String value) { this.columns = value; }
    private String parameter1;
    public String getParameter1() { return parameter1; }
    public void setParameter1(String value) { this.parameter1 = value; }
    private String parameter2;
    public String getParameter2() { return parameter2; }
    public void setParameter2(String value) { this.parameter2 = value; }
    private String referenceTable;
    public String getReferenceTable() { return referenceTable; }
    public void setReferenceTable(String value) { this.referenceTable = value; }
    private String referenceColumn;
    public String getReferenceColumn() { return referenceColumn; }
    public void setReferenceColumn(String value) { this.referenceColumn = value; }
    private long toleranceCount;
    public long getToleranceCount() { return toleranceCount; }
    public void setToleranceCount(long value) { this.toleranceCount = value; }
    private java.math.BigDecimal tolerancePercent = java.math.BigDecimal.ZERO;
    public java.math.BigDecimal getTolerancePercent() { return tolerancePercent; }
    public void setTolerancePercent(java.math.BigDecimal value) { this.tolerancePercent = value; }
    private String severity = "CRITICAL";
    public String getSeverity() { return severity; }
    public void setSeverity(String value) { this.severity = value; }
}
