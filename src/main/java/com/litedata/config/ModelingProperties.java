package com.litedata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DDL uses the warehouse writer; API queries retain the separate reader identity. */
@ConfigurationProperties("platform.modeling")
public record ModelingProperties(String username, String password) {}
