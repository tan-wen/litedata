package com.litedata.security;

import com.litedata.config.PlatformProperties;
import com.litedata.store.PlatformMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements ApplicationRunner {
    private final PlatformMapper mapper; private final PasswordEncoder encoder; private final PlatformProperties properties;
    public AdminInitializer(PlatformMapper mapper, PasswordEncoder encoder, PlatformProperties properties) {
        this.mapper = mapper; this.encoder = encoder; this.properties = properties;
    }
    public void run(ApplicationArguments args) {
        if (properties.internalToken() == null || properties.internalToken().length() < 32)
            throw new IllegalArgumentException("PLATFORM_INTERNAL_TOKEN 至少32字符");
        if (mapper.findUser("admin") != null) return;
        String password = properties.adminPassword();
        if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("首次启动需设置12字符以上、UTF-8不超过72字节的 PLATFORM_ADMIN_PASSWORD");
        mapper.insertUser("admin", encoder.encode(password), "ADMIN");
    }
}

