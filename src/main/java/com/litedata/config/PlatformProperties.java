package com.litedata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform")
public record PlatformProperties(String encryptionKey, String adminPassword, String internalToken,
                                 DolphinScheduler dolphinscheduler, Doris doris) {
    public record DolphinScheduler(String url, String token, long projectCode, String tenantCode) {}
    public record Doris(String jdbcUrl, String username, String password) {}
}
