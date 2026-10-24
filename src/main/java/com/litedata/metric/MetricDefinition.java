package com.litedata.metric;

import java.util.ArrayList;
import java.util.List;

public class MetricDefinition {
    private Long id;
    private String code = "";
    private String name = "";
    private MetricType type = MetricType.ATOMIC;
    private String subjectArea = "";
    private String owner = "";
    private String unit = "";
    private String description = "";
    private String dimensions = "";
    private Long modelId;
    private Integer modelVersion;
    private String aggregation = "SUM";
    private String measureColumn = "";
    private String timeColumn = "";
    private Long baseMetricId;
    private int windowDays = 7;
    private String filterExpression = "";
    private String formula = "";
    private int version;
    private boolean archived;
    private String sqlText = "";
    private List<MetricDependency> dependencies = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public MetricType getType() { return type; }
    public void setType(MetricType type) { this.type = type; }
    public String getSubjectArea() { return subjectArea; }
    public void setSubjectArea(String subjectArea) { this.subjectArea = subjectArea; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDimensions() { return dimensions; }
    public void setDimensions(String dimensions) { this.dimensions = dimensions; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public Integer getModelVersion() { return modelVersion; }
    public void setModelVersion(Integer modelVersion) { this.modelVersion = modelVersion; }
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }
    public String getMeasureColumn() { return measureColumn; }
    public void setMeasureColumn(String measureColumn) { this.measureColumn = measureColumn; }
    public String getTimeColumn() { return timeColumn; }
    public void setTimeColumn(String timeColumn) { this.timeColumn = timeColumn; }
    public Long getBaseMetricId() { return baseMetricId; }
    public void setBaseMetricId(Long baseMetricId) { this.baseMetricId = baseMetricId; }
    public int getWindowDays() { return windowDays; }
    public void setWindowDays(int windowDays) { this.windowDays = windowDays; }
    public String getFilterExpression() { return filterExpression; }
    public void setFilterExpression(String filterExpression) { this.filterExpression = filterExpression; }
    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }
    public List<MetricDependency> getDependencies() { return dependencies; }
    public void setDependencies(List<MetricDependency> dependencies) { this.dependencies = dependencies; }
}
