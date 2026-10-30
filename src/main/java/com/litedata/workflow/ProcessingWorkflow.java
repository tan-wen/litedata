package com.litedata.workflow;

public class ProcessingWorkflow {
    private Long id;
    private String name;
    private String description="";
    private Long rootTaskId;
    private int version;
    private String positionsJson;
    private String rootName;
    private String cronExpression;
    private String status;
    public Long getId() { return id; }
    public void setId(Long id) { this.id=id; }
    public String getName() { return name; }
    public void setName(String name) { this.name=name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description=description; }
    public Long getRootTaskId() { return rootTaskId; }
    public void setRootTaskId(Long rootTaskId) { this.rootTaskId=rootTaskId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version=version; }
    public String getPositionsJson() { return positionsJson; }
    public void setPositionsJson(String positionsJson) { this.positionsJson=positionsJson; }
    public String getRootName() { return rootName; }
    public void setRootName(String rootName) { this.rootName=rootName; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression=cronExpression; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status=status; }
}
