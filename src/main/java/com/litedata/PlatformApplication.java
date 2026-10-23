package com.litedata;

import com.litedata.config.PlatformProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformApplication {
    public static void main(String[] args) { SpringApplication.run(PlatformApplication.class, args); }
}

