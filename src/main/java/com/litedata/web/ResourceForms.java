package com.litedata.web;

/** Only user-editable form fields; credentials and runtime state are never bound. */
public final class ResourceForms {
    private ResourceForms() {}

    public static class SourceDraft {
        private String databaseType = "";
        public String getDatabaseType() { return databaseType; }
        public void setDatabaseType(String databaseType) { this.databaseType=databaseType; }
        private String name = "";
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class PipelineDraft {
        private String name = "";
        private Long sourceId;
        private String sourceTable = "";
        private String watermarkColumn = "";
        private String targetTable = "";
        private String transformSql = "";
        private String cronExpression = "0 0 * * * ?";
        private String targetMode = "EXISTING";
        private java.util.List<String> keyColumns = new java.util.ArrayList<>();
        private String previewHash = "";
        private boolean confirmed;
        private boolean autoBuckets = true;
        private int buckets = 1;
        private int replicationNum = 1;
        private int wizardStep = 1;
        private String distributionType = "HASH";
        private String distributionColumns = "";
        private String partitionType = "NONE";
        private String partitionColumn = "";
        private String partitionUnit = "DAY";
        private String partitionRangeStyle = "FIXED";
        private String dynamicPartitionProperties = com.litedata.model.DataModel.DEFAULT_DYNAMIC_PARTITION_PROPERTIES;
        private java.util.List<com.litedata.model.ModelPartition> partitions = new java.util.ArrayList<>();
        public int getWizardStep() { return wizardStep; }
        public void setWizardStep(int value) { wizardStep=Math.clamp(value,1,4); }
        public String getDistributionType() { return distributionType; }
        public void setDistributionType(String value) { distributionType=value; }
        public String getDistributionColumns() { return distributionColumns; }
        public void setDistributionColumns(String value) { distributionColumns=value; }
        public String getPartitionType() { return partitionType; }
        public void setPartitionType(String value) { partitionType=value; }
        public String getPartitionColumn() { return partitionColumn; }
        public void setPartitionColumn(String value) { partitionColumn=value; }
        public String getPartitionUnit() { return partitionUnit; }
        public void setPartitionUnit(String value) { partitionUnit=value; }
        public String getPartitionRangeStyle() { return partitionRangeStyle; }
        public void setPartitionRangeStyle(String value) { partitionRangeStyle=value; }
        public String getDynamicPartitionProperties() { return dynamicPartitionProperties; }
        public void setDynamicPartitionProperties(String value) { dynamicPartitionProperties=value; }
        public java.util.List<com.litedata.model.ModelPartition> getPartitions() { return partitions; }
        public void setPartitions(java.util.List<com.litedata.model.ModelPartition> value) { partitions=value; }
        public String getTargetMode() { return targetMode; }
        public void setTargetMode(String value) { targetMode=value; }
        public java.util.List<String> getKeyColumns() { return keyColumns; }
        public void setKeyColumns(java.util.List<String> value) { keyColumns=value; }
        public String getPreviewHash() { return previewHash; }
        public void setPreviewHash(String value) { previewHash=value; }
        public boolean isConfirmed() { return confirmed; }
        public void setConfirmed(boolean value) { confirmed=value; }
        public boolean isAutoBuckets() { return autoBuckets; }
        public void setAutoBuckets(boolean value) { autoBuckets=value; }
        public int getBuckets() { return buckets; }
        public void setBuckets(int value) { buckets=value; }
        public int getReplicationNum() { return replicationNum; }
        public void setReplicationNum(int value) { replicationNum=value; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getSourceId() { return sourceId; }
        public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
        public String getSourceTable() { return sourceTable; }
        public void setSourceTable(String sourceTable) { this.sourceTable = sourceTable; }
        public String getWatermarkColumn() { return watermarkColumn; }
        public void setWatermarkColumn(String watermarkColumn) { this.watermarkColumn = watermarkColumn; }
        public String getTargetTable() { return targetTable; }
        public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
        public String getTransformSql() { return transformSql; }
        public void setTransformSql(String transformSql) { this.transformSql = transformSql; }
        public String getCronExpression() { return cronExpression; }
        public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    }

    public static class ServiceDraft {
        private String name = "";
        private String slug = "";
        private String querySql = "";
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getQuerySql() { return querySql; }
        public void setQuerySql(String querySql) { this.querySql = querySql; }
    }

    public static class UserDraft {
        private String username = "";
        private String password = "";
        private String role = "VIEWER";
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }
}
