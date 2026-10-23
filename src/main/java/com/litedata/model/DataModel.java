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
    private int buckets = 1;
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
    public int getBuckets() { return buckets; }
    public void setBuckets(int buckets) { this.buckets = buckets; }
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
