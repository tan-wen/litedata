package com.litedata.security;

import java.time.LocalDateTime;

/** Persistence input only. Never serialize credentials or add a generated toString. */
public class UserApiKey {
    private Long id;
    private final long userId;
    private final String name,tokenHash,tokenPrefix,scopes,serviceSlugs,pipelineIds;
    private final LocalDateTime expiresAt;
    public UserApiKey(long userId,String name,String tokenHash,String tokenPrefix,String scopes,
                      String serviceSlugs,String pipelineIds,LocalDateTime expiresAt) {
        this.userId=userId; this.name=name; this.tokenHash=tokenHash; this.tokenPrefix=tokenPrefix;
        this.scopes=scopes; this.serviceSlugs=serviceSlugs; this.pipelineIds=pipelineIds; this.expiresAt=expiresAt;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id=id; }
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public String getTokenHash() { return tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public String getScopes() { return scopes; }
    public String getServiceSlugs() { return serviceSlugs; }
    public String getPipelineIds() { return pipelineIds; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
