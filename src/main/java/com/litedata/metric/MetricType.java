package com.litedata.metric;

public enum MetricType {
    ATOMIC("原子指标"), COMPOSITE("复合指标"), DERIVED("派生指标");
    private final String label;
    MetricType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
