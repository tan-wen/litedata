package com.litedata.model;

/** Structured single-column manual partition definition; values are data, never SQL. */
public class ModelPartition {
    private String name = "";
    private String lowerBound = "";
    private String upperBound = "";
    private String valuesText = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLowerBound() { return lowerBound; }
    public void setLowerBound(String lowerBound) { this.lowerBound = lowerBound; }
    public String getUpperBound() { return upperBound; }
    public void setUpperBound(String upperBound) { this.upperBound = upperBound; }
    public String getValuesText() { return valuesText; }
    public void setValuesText(String valuesText) { this.valuesText = valuesText; }
}
