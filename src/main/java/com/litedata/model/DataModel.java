package com.litedata.model;

import java.util.ArrayList;
import java.util.List;

public class DataModel {
    private Long id;
    private String tableName;
    private String name;
    private String subjectArea;
    private String layer = "ODS";
    private String description = "";
    private String dimensionKeys = "";
    private String dimensionLabel = "";
    private String dimensionStrategy = "";
    private int buckets = 1;
    private boolean autoBuckets;
    private String distributionType = "HASH";
    // Null keeps legacy models/clients on all key columns until explicitly configured.
    private String distributionColumns;
    private String modelType = "UNIQUE";
    private int replicationNum = 1;
    private String partitionType = "NONE";
    private String partitionColumn = "";
    private String partitionUnit = "DAY";
    private String partitionRangeStyle = "FIXED";
    public static final String DEFAULT_DYNAMIC_PARTITION_PROPERTIES = """
            PROPERTIES(
                "dynamic_partition.enable" = "true",
                "dynamic_partition.time_unit" = "DAY",
                "dynamic_partition.start" = "-1",
                "dynamic_partition.end" = "2",
                "dynamic_partition.prefix" = "p",
                "dynamic_partition.create_history_partition" = "true"
            )""";
    private String dynamicPartitionProperties = DEFAULT_DYNAMIC_PARTITION_PROPERTIES;
    private List<ModelPartition> partitions = new ArrayList<>();
    private int version;
    private Integer physicalVersion;
    private boolean archived;
    private List<ModelField> fields = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubjectArea() { return subjectArea; }
    public void setSubjectArea(String subjectArea) { this.subjectArea = subjectArea; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDimensionKeys() { return dimensionKeys; }
    public void setDimensionKeys(String dimensionKeys) { this.dimensionKeys = dimensionKeys; }
    public String getDimensionLabel() { return dimensionLabel; }
    public void setDimensionLabel(String dimensionLabel) { this.dimensionLabel = dimensionLabel; }
    public String getDimensionStrategy() { return dimensionStrategy; }
    public void setDimensionStrategy(String dimensionStrategy) { this.dimensionStrategy = dimensionStrategy; }
    public String dimensionRole(ModelField field) {
        if (!"DIM".equals(layer)) return "—";
        if (field.isKeyColumn()) return "业务键";
        return field.getName().equals(dimensionLabel) ? "展示名称" : "维度属性";
    }
    public int getBuckets() { return buckets; }
    public void setBuckets(int buckets) { this.buckets = buckets; }
    public boolean isAutoBuckets() { return autoBuckets; }
    public void setAutoBuckets(boolean autoBuckets) { this.autoBuckets = autoBuckets; }
    public String getDistributionType() { return distributionType; }
    public void setDistributionType(String distributionType) { this.distributionType = distributionType; }
    public String getDistributionColumns() { return distributionColumns; }
    public void setDistributionColumns(String distributionColumns) { this.distributionColumns = distributionColumns; }
    public List<String> getDistributionKeyColumns() {
        if (distributionColumns == null)
            return fields.stream().filter(ModelField::isKeyColumn).map(ModelField::getName).toList();
        if (distributionColumns.isBlank()) return List.of();
        return java.util.Arrays.stream(distributionColumns.split(",", -1)).map(String::trim).toList();
    }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public int getReplicationNum() { return replicationNum; }
    public void setReplicationNum(int replicationNum) { this.replicationNum = replicationNum; }
    public String getPartitionType() { return partitionType; }
    public void setPartitionType(String partitionType) { this.partitionType = partitionType; }
    public String getPartitionColumn() { return partitionColumn; }
    public void setPartitionColumn(String partitionColumn) { this.partitionColumn = partitionColumn; }
    public String getPartitionUnit() { return partitionUnit; }
    public void setPartitionUnit(String partitionUnit) { this.partitionUnit = partitionUnit; }
    public String getPartitionRangeStyle() { return partitionRangeStyle; }
    public void setPartitionRangeStyle(String partitionRangeStyle) { this.partitionRangeStyle = partitionRangeStyle; }
    public String getDynamicPartitionProperties() { return dynamicPartitionProperties == null ? DEFAULT_DYNAMIC_PARTITION_PROPERTIES : dynamicPartitionProperties; }
    public void setDynamicPartitionProperties(String properties) { this.dynamicPartitionProperties = properties; }
    public List<ModelPartition> getPartitions() { return partitions; }
    public void setPartitions(List<ModelPartition> partitions) { this.partitions = partitions; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Integer getPhysicalVersion() { return physicalVersion; }
    public void setPhysicalVersion(Integer physicalVersion) { this.physicalVersion = physicalVersion; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public List<ModelField> getFields() { return fields; }
    public void setFields(List<ModelField> fields) { this.fields = fields; }
    public String getPhysicalStatus() {
        return physicalVersion == null ? "待建表 / 待核对" : physicalVersion == version ? "已核对" : "模型已变更";
    }
}
