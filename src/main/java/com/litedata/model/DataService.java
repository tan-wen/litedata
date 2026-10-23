package com.litedata.model;

public class DataService {
    private Long id;
    private String name;
    private String slug;
    private String querySql;
    private int maxRows;
    private boolean published;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getQuerySql() { return querySql; }
    public void setQuerySql(String querySql) { this.querySql = querySql; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
}

