package com.litedata.catalog;

import java.time.*;

/** Optional business annotations; empty fields inherit the current resource definition. */
public class AssetProfile {
    private String businessName="",subjectArea="",owner="",description="",tags="",updateFrequency="",sensitivity="INTERNAL",updatedBy;
    private int version;
    private Long updatedEpoch;
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String value) { businessName=value; }
    public String getSubjectArea() { return subjectArea; }
    public void setSubjectArea(String value) { subjectArea=value; }
    public String getOwner() { return owner; }
    public void setOwner(String value) { owner=value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description=value; }
    public String getTags() { return tags; }
    public void setTags(String value) { tags=value; }
    public String getUpdateFrequency() { return updateFrequency; }
    public void setUpdateFrequency(String value) { updateFrequency=value; }
    public String getSensitivity() { return sensitivity; }
    public void setSensitivity(String value) { sensitivity=value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { version=value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy=value; }
    public Long getUpdatedEpoch() { return updatedEpoch; }
    public void setUpdatedEpoch(Long value) { updatedEpoch=value; }
    public LocalDateTime getUpdatedAt() { return updatedEpoch==null?null:LocalDateTime.ofInstant(Instant.ofEpochSecond(updatedEpoch),ZoneId.of("Asia/Shanghai")); }
}
