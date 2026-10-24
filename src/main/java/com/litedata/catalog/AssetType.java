package com.litedata.catalog;

public enum AssetType {
    TABLE("物理表"), MODEL("逻辑模型"), METRIC("指标"), SERVICE("数据服务");
    private final String label;
    AssetType(String label) { this.label=label; }
    public String getLabel() { return label; }
}
