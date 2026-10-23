package com.litedata.service;

import com.litedata.config.PlatformProperties;
import com.litedata.data.JdbcConnections;
import com.litedata.model.DataService;
import com.litedata.store.PlatformMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DataApiService {
    private final PlatformMapper mapper; private final QueryPolicy policy; private final MyBatisRuntimeQueries queries;
    private final Semaphore concurrent=new Semaphore(8);
    private final ConcurrentMap<String,Window> limits=new ConcurrentHashMap<>();
    public DataApiService(PlatformMapper mapper,QueryPolicy policy,MyBatisRuntimeQueries queries) {
        this.mapper=mapper; this.policy=policy; this.queries=queries;
    }
    public void create(String name,String slug,String sql,int maxRows) {
        if(name==null || name.isBlank() || name.length()>100 || slug==null || !slug.matches("[a-z][a-z0-9-]{0,79}"))
            throw new IllegalArgumentException("名称最长100字符；服务标识使用小写字母、数字和连字符");
        if(maxRows<1 || maxRows>10000) throw new IllegalArgumentException("结果行数须在1到10000之间");
        policy.validate(sql);
        DataService service=new DataService(); service.setName(name); service.setSlug(slug); service.setQuerySql(sql); service.setMaxRows(maxRows);
        mapper.insertService(service);
    }
    public void publish(long id) {
        DataService service=mapper.dataService(id);
        if(service==null) throw new IllegalArgumentException("服务不存在");
        policy.validate(service.getQuerySql()); mapper.publish(id,!service.isPublished());
    }
    @Transactional
    public String credential(long id) {
        if(mapper.dataService(id)==null) throw new IllegalArgumentException("服务不存在");
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token="ld_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mapper.revokeCredentials(id); mapper.insertCredential(id,hash(token)); return token;
    }
    public Map<String,Object> query(String slug,String token,Map<String,String> params) {
        DataService service=mapper.publishedService(slug);
        if(service==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"服务不存在或尚未发布");
        String digest=hash(token==null?"":token);
        if(token==null || token.length()>128 || mapper.credentialValid(service.getId(),digest)==0)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"API凭证无效");
        if(limits.size()>10000) limits.entrySet().removeIf(e->e.getValue().minute()<Instant.now().getEpochSecond()/60-1);
        Window window=limits.compute(digest,(key,old)->{
            long minute=Instant.now().getEpochSecond()/60;
            return old==null || old.minute()!=minute?new Window(minute,1):new Window(minute,old.count()+1);
        });
        if(window.count()>60) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"每分钟最多60次请求");
        QueryPolicy.BoundQuery query=policy.validate(service.getQuerySql());
        if(!new HashSet<>(query.parameters()).equals(params.keySet()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请求参数与服务定义不一致");
        if(!concurrent.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"查询并发已满");
        String outcome="FAILED";
        try {
            List<String> values=new ArrayList<>();
            for(String name:query.parameters()) {
                String value=params.get(name);
                if(value.length()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"参数值过长");
                values.add(value);
            }
            List<Map<String,Object>> results=queries.execute(query,values,service.getMaxRows()+1);
            boolean truncated=results.size()>service.getMaxRows();
            List<Map<String,Object>> rows=truncated?results.subList(0,service.getMaxRows()):results;
            outcome="SUCCESS";
            return Map.of("data",rows,"count",rows.size(),"truncated",truncated);
        } catch(org.apache.ibatis.exceptions.PersistenceException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"数据查询失败或超时");
        } finally {
            concurrent.release(); mapper.audit("api:"+service.getId(),"SERVICE_QUERY",service.getSlug(),outcome);
        }
    }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record Window(long minute,int count) {}
}

