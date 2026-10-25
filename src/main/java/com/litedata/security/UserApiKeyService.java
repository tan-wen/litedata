package com.litedata.security;

import com.litedata.model.AppUser;
import com.litedata.service.DataApiService;
import com.litedata.service.QueryPage;
import com.litedata.store.PlatformMapper;
import com.litedata.store.UserApiKeyMapper;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserApiKeyService {
    public static final String READ="services:read",QUERY="services:query",RUN="pipelines:run";
    private static final Set<String> ROLES=Set.of("ADMIN","DEVELOPER","VIEWER");
    private final UserApiKeyMapper keys;
    private final PlatformMapper platform;
    private final SecureRandom random=new SecureRandom();
    public UserApiKeyService(UserApiKeyMapper keys,PlatformMapper platform) { this.keys=keys; this.platform=platform; }

    public long count(String username) { return keys.count(owner(username).getId()); }
    public List<ApiKeyView> list(String username,QueryPage page) {
        return keys.page(owner(username).getId(),page.pageSize(),page.offset());
    }
    public Set<String> allowedScopes(String username) { return scopesForRole(owner(username).getRole()); }
    private static Set<String> scopesForRole(String role) {
        if(!ROLES.contains(role)) return Set.of();
        return "VIEWER".equals(role)?Set.of(READ,QUERY):Set.of(READ,QUERY,RUN);
    }
    @Transactional
    public IssuedKey create(String username,String name,int validDays,Set<String> scopes,String serviceSlugs,String pipelineIds) {
        var user=owner(username);
        if(name==null || name.isBlank() || name.length()>100 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Key 名称须为1到100个可见字符");
        if(validDays<1 || validDays>365) throw new IllegalArgumentException("有效期须为1到365天");
        if(scopes==null || scopes.isEmpty() || !scopesForRole(user.getRole()).containsAll(scopes))
            throw new IllegalArgumentException("请选择当前角色允许的权限范围");
        var slugs=csv(serviceSlugs); var pipelineValues=csv(pipelineIds);
        if((scopes.contains(READ)||scopes.contains(QUERY)) && slugs.isEmpty())
            throw new IllegalArgumentException("数据服务权限须指定至少一个服务标识");
        if(!slugs.isEmpty() && !scopes.contains(READ) && !scopes.contains(QUERY))
            throw new IllegalArgumentException("服务白名单需要数据服务权限");
        if(scopes.contains(RUN)!=!pipelineValues.isEmpty()) throw new IllegalArgumentException("管道执行权限须同时指定管道白名单");
        var serviceIds=new ArrayList<Long>(); var pipelines=new ArrayList<Long>();
        for(String slug:slugs) {
            if(!slug.matches("[a-z][a-z0-9-]{0,79}")) throw new IllegalArgumentException("服务标识格式有误");
            var service=platform.serviceBySlug(slug);
            if(service==null || !service.isPublished()) throw new IllegalArgumentException("服务白名单须为已发布的数据服务");
            serviceIds.add(service.getId());
        }
        for(String value:pipelineValues) {
            if(!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("管道ID须为正整数，不能带符号或前导零");
            long id;
            try { id=Long.parseLong(value); } catch(NumberFormatException e) { throw new IllegalArgumentException("管道ID须为正整数"); }
            if(id<1 || platform.pipeline(id)==null) throw new IllegalArgumentException("管道白名单包含无效的管道ID");
            pipelines.add(id);
        }
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token="ld_mcp_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var key=new UserApiKey(user.getId(),name.strip(),DataApiService.hash(token),token.substring(0,15),
                String.join(",",new TreeSet<>(scopes)),String.join(",",slugs),String.join(",",pipelineValues),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(validDays));
        keys.insert(key);
        for(long id:serviceIds) keys.grantService(key.getId(),id);
        for(long id:pipelines) keys.grantPipeline(key.getId(),id);
        platform.audit(user.getUsername(),"USER_KEY_CREATE",Long.toString(key.getId()),"SUCCESS");
        return new IssuedKey(keys.view(key.getId(),user.getId()),token);
    }
    @Transactional
    public void revoke(String username,long id) {
        var user=owner(username);
        if(keys.view(id,user.getId())==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Key 不存在");
        keys.revoke(id,user.getId());
        platform.audit(user.getUsername(),"USER_KEY_REVOKE",Long.toString(id),"SUCCESS");
    }
    public Identity authenticate(String token) {
        if(token==null || !token.matches("ld_mcp_[A-Za-z0-9_-]{43}")) throw unauthorized();
        return valid(keys.authenticate(DataApiService.hash(token)));
    }
    public Identity refresh(Identity identity) { return valid(keys.refresh(identity.keyId(),identity.userId())); }
    public void touch(Identity identity) { keys.touch(identity.keyId()); }
    public boolean hasScope(Identity identity,String scope) {
        return scopesForRole(identity.role()).contains(scope) && Arrays.asList(identity.scopes().split(",")).contains(scope);
    }
    public Identity requireScope(Identity identity,String scope) {
        var current=refresh(identity);
        if(!hasScope(current,scope)) throw forbidden();
        return current;
    }
    public Identity requireService(Identity identity,long id,String scope) {
        var current=requireScope(identity,scope);
        if(keys.serviceGranted(current.keyId(),id)==0) throw forbidden();
        return current;
    }
    public Identity requirePipeline(Identity identity,long id) {
        var current=requireScope(identity,RUN);
        if(keys.pipelineGranted(current.keyId(),id)==0) throw forbidden();
        return current;
    }
    private AppUser owner(String username) {
        var user=platform.findUser(username);
        if(user==null || !user.isEnabled() || !ROLES.contains(user.getRole())) throw unauthorized();
        return user;
    }
    private Identity valid(Identity identity) {
        if(identity==null || !ROLES.contains(identity.role())) throw unauthorized();
        return identity;
    }
    private static SortedSet<String> csv(String values) {
        var result=new TreeSet<String>();
        if(values==null || values.isBlank()) return result;
        if(values.length()>10000) throw new IllegalArgumentException("资源白名单过长");
        for(String value:values.split(",",-1)) {
            if(value.isBlank()) throw new IllegalArgumentException("资源白名单请使用逗号分隔，不能包含空值");
            result.add(value.strip());
        }
        if(result.size()>100) throw new IllegalArgumentException("每种资源最多授权100个");
        return result;
    }
    private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"MCP 凭证无效或已过期"); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"当前用户或 Key 无权执行此操作"); }
    public record Identity(long keyId,long userId,String username,String role,String scopes) {}
    public record ApiKeyView(long id,String name,String tokenPrefix,String scopes,String serviceSlugs,String pipelineIds,
                             LocalDateTime createdAt,LocalDateTime expiresAt,LocalDateTime lastUsedAt,LocalDateTime revokedAt) {}
    public record IssuedKey(ApiKeyView key,String token) {
        @Override public String toString() { return "IssuedKey[keyId="+key.id()+"]"; }
    }
}
