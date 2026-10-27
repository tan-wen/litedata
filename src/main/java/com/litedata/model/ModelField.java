package com.litedata.model;

public class ModelField {
    private String name;
    private String dataType;
    private boolean keyColumn;
    private boolean nullable;
    private String comment = "";
    private String standard = "";
    private String aggregation = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public boolean isKeyColumn() { return keyColumn; }
    public void setKeyColumn(boolean keyColumn) { this.keyColumn = keyColumn; }
    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getStandard() { return standard; }
    public void setStandard(String standard) { this.standard = standard; }
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }
}
