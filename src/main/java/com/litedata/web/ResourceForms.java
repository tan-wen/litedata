package com.litedata.web;

/** Only user-editable form fields; credentials and runtime state are never bound. */
public final class ResourceForms {
    private ResourceForms() {}

    public static class SourceDraft {
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
