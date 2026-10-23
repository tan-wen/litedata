package com.litedata.model;

import java.time.LocalDateTime;

public class ServiceCall {
    private Long id;
    private Long serviceId;
    private LocalDateTime startedAt;
    private long durationMs;
    private int statusCode;
    private int rowCount;
    private Integer pageNum;
    private Integer pageSize;
    public Long getId() { return id; }
    public void setId(Long id) { this.id=id; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId=serviceId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt=startedAt; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs=durationMs; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode=statusCode; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount=rowCount; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum=pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize=pageSize; }
}
