package com.litedata.service;

import com.litedata.model.DataService;
import com.litedata.model.ServiceCall;
import com.litedata.security.UserApiKeyService;
import com.litedata.security.UserApiKeyService.Identity;
import com.litedata.store.PlatformMapper;
import com.litedata.store.ServiceCallMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DataApiService {
    private final PlatformMapper mapper; private final QueryPolicy policy; private final MyBatisRuntimeQueries queries;
    private final ServiceCallMapper calls;
    private final UserApiKeyService userKeys;
    private static final ZoneId DISPLAY_ZONE=ZoneId.of("Asia/Shanghai");
    private final Semaphore concurrent=new Semaphore(8);
    private final ConcurrentMap<String,Window> limits=new ConcurrentHashMap<>();
    public DataApiService(PlatformMapper mapper,QueryPolicy policy,MyBatisRuntimeQueries queries,ServiceCallMapper calls,UserApiKeyService userKeys) {
        this.mapper=mapper; this.policy=policy; this.queries=queries; this.calls=calls; this.userKeys=userKeys;
    }
    public void create(String name,String slug,String sql) {
        mapper.insertService(definition(name,slug,sql));
    }
    public void update(long id,String name,String slug,String sql) {
        var existing=get(id);
        if(!existing.getSlug().equals(slug)) throw new IllegalArgumentException("服务标识创建后固定，修改 API 路径请新建服务");
        var service=definition(name,slug,sql);
        service.setId(id);
        mapper.updateService(service);
    }
    private DataService definition(String name,String slug,String sql) {
        if(name==null || name.isBlank() || name.length()>100 || slug==null || !slug.matches("[a-z][a-z0-9-]{0,79}"))
            throw new IllegalArgumentException("名称最长100字符；服务标识使用小写字母、数字和连字符");
        policy.validate(sql);
        DataService service=new DataService(); service.setName(name); service.setSlug(slug); service.setQuerySql(sql);
        return service;
    }
    public List<DataService> list() {
        var day=LocalDate.now(DISPLAY_ZONE);
        var start=day.atStartOfDay(DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var end=day.plusDays(1).atStartOfDay(DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return mapper.servicesWithDailyCalls(start,end);
    }
    public List<DataService> list(QueryPage page) {
        var day=LocalDate.now(DISPLAY_ZONE);
        var start=day.atStartOfDay(DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var end=day.plusDays(1).atStartOfDay(DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return mapper.servicePage(start,end,page.pageSize(),page.offset());
    }
    public DataService get(long id) {
        var service=mapper.dataService(id);
        if(service==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"服务不存在");
        return service;
    }
    public List<ServiceCall> calls(long id,QueryPage page) {
        var records=calls.calls(id,page.pageSize()+1,page.offset());
        for(var call:records) call.setStartedAt(call.getStartedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(DISPLAY_ZONE).toLocalDateTime());
        return records;
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
        return query(slug,token,null,params);
    }
    public Map<String,Object> queryForUser(String slug,Identity identity,Map<String,String> params) {
        Objects.requireNonNull(identity);
        return query(slug,null,identity,params);
    }
    private Map<String,Object> query(String slug,String token,Identity identity,Map<String,String> params) {
        Instant startedAt=Instant.now();
        long started=System.nanoTime();
        DataService service=mapper.serviceBySlug(slug);
        if(service==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"服务不存在或尚未发布");
        ServiceCall call=new ServiceCall(); call.setServiceId(service.getId());
        call.setStartedAt(LocalDateTime.ofInstant(startedAt,ZoneOffset.UTC)); call.setStatusCode(500);
        boolean acquired=false;
        try {
            if(!service.isPublished()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"服务不存在或尚未发布");
            String digest;
            if(identity==null) {
                digest=hash(token==null?"":token);
                if(token==null || token.length()>128 || mapper.credentialValid(service.getId(),digest)==0)
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"API凭证无效");
            } else {
                userKeys.requireService(identity,service.getId(),UserApiKeyService.QUERY);
                digest="mcp:"+identity.keyId();
            }
            if(limits.size()>10000) limits.entrySet().removeIf(e->e.getValue().minute()<Instant.now().getEpochSecond()/60-1);
            Window window=limits.compute(digest,(key,old)->{
                long minute=Instant.now().getEpochSecond()/60;
                return old==null || old.minute()!=minute?new Window(minute,1):new Window(minute,old.count()+1);
            });
            if(window.count()>60) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"每分钟最多60次请求");
            QueryPage page=QueryPage.parse(params,100);
            call.setPageNum(page.pageNum()); call.setPageSize(page.pageSize());
            QueryPolicy.BoundQuery query=policy.validate(service.getQuerySql());
            Set<String> businessParams=new HashSet<>(params.keySet());
            businessParams.removeAll(Set.of("pageNum","pageSize"));
            if(!new HashSet<>(query.parameters()).equals(businessParams))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请求参数与服务定义不一致");
            List<String> values=new ArrayList<>();
            for(String name:query.parameters()) {
                String value=params.get(name);
                if(value==null || value.length()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"参数值缺失或过长");
                values.add(value);
            }
            if(!concurrent.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"查询并发已满");
            acquired=true;
            List<Map<String,Object>> results=queries.execute(query,values,page.pageSize()+1,page.offset());
            boolean hasMore=results.size()>page.pageSize();
            List<Map<String,Object>> rows=hasMore?results.subList(0,page.pageSize()):results;
            call.setStatusCode(200); call.setRowCount(rows.size());
            return Map.of("data",rows,"count",rows.size(),"pageNum",page.pageNum(),"pageSize",page.pageSize(),"hasMore",hasMore);
        } catch(ResponseStatusException e) {
            call.setStatusCode(e.getStatusCode().value()); throw e;
        } catch(IllegalArgumentException e) {
            call.setStatusCode(400); throw e;
        } catch(org.apache.ibatis.exceptions.PersistenceException | IllegalStateException e) {
            call.setStatusCode(502);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"数据查询失败或超时");
        } finally {
            if(acquired) concurrent.release();
            call.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            calls.insert(call);
            mapper.audit(identity==null?"api:"+service.getId():identity.username(),"SERVICE_QUERY",
                    identity==null?service.getSlug():"key:"+identity.keyId()+"/service:"+service.getId(),call.getStatusCode()==200?"SUCCESS":"FAILED");
        }
    }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record Window(long minute,int count) {}
}

