package com.litedata.workflow;

public class ProcessingWorkflow {
    private Long id;
    private String name;
    private String description="";
    private int version;
    private String positionsJson;
    private String cronExpression="0 0 * * * ?";
    private String status;
    private Long definitionCode;
    private String definitionHash;
    private Integer scheduleId;
    private boolean archived;
    private boolean reviewRequired;
    private String ownershipNotice;
    private java.time.LocalDateTime lastRunAt;
    public java.time.LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(java.time.LocalDateTime value) { lastRunAt=value; }
    public boolean isReviewRequired() { return reviewRequired; }
    public void setReviewRequired(boolean value) { reviewRequired=value; }
    public String getOwnershipNotice() { return ownershipNotice; }
    public void setOwnershipNotice(String value) { ownershipNotice=value; }
    public Long getId() { return id; }
    public void setId(Long value) { this.id=value; }
    public String getName() { return name; }
    public void setName(String value) { this.name=value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description=value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { this.version=value; }
    public String getPositionsJson() { return positionsJson; }
    public void setPositionsJson(String value) { this.positionsJson=value; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String value) { this.cronExpression=value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status=value; }
    public Long getDefinitionCode() { return definitionCode; }
    public void setDefinitionCode(Long value) { this.definitionCode=value; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String value) { this.definitionHash=value; }
    public Integer getScheduleId() { return scheduleId; }
    public void setScheduleId(Integer value) { this.scheduleId=value; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean value) { this.archived=value; }
}
