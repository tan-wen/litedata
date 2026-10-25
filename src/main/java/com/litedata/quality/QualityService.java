package com.litedata.quality;

import com.litedata.store.*;
import com.litedata.service.QueryPage;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@EnableScheduling
public class QualityService {
    private final QualityMapper mapper;
    private final PlatformMapper platform;
    private final QualityValidation validation;
    private final QualityScanner scanner;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final boolean scheduling;
    private volatile boolean ready;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),
            Thread.ofPlatform().name("quality-scan-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public QualityService(QualityMapper mapper,PlatformMapper platform,QualityValidation validation,QualityScanner scanner,
            org.springframework.transaction.PlatformTransactionManager manager,ObjectMapper json,
            @Value("${platform.quality.scheduling-enabled:true}") boolean scheduling) {
        this.mapper=mapper; this.platform=platform; this.validation=validation; this.scanner=scanner;
        this.transactions=new TransactionTemplate(manager); this.json=json; this.scheduling=scheduling;
    }
    public String keyword(String keyword) {
        String value=Objects.toString(keyword,"").trim(); if(value.length()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"搜索词最长100字符"); return value;
    }
    public long count(String keyword) { return mapper.count(keyword(keyword)); }
    public List<QualityTask> list(String keyword,QueryPage page) { return mapper.page(keyword(keyword),page.pageSize(),page.offset()); }
    public QualityTask require(long id) { var t=required(mapper.task(id)); t.setRules(mapper.rules(id)); return t; }
    public QualityRun run(long id) {
        var r=mapper.run(id); if(r==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"扫描记录不存在"); return r;
    }
    public long runCount(long id) { return mapper.runCount(id); }
    public List<QualityRun> runs(long id,QueryPage page) { return mapper.runs(id,page.pageSize(),page.offset()); }
    public List<QualityResult> results(long id) { run(id); return mapper.results(id); }
    public long save(Long id,QualityTask input,String actor) {
        validation.validate(input);
        if(input.getSourceId()!=null && platform.source(input.getSourceId())==null) throw new IllegalArgumentException("数据源不存在");
        if(input.getSourceId()!=null && platform.source(input.getSourceId()).getDatabaseType()!=com.litedata.data.DatabaseType.MYSQL)
            throw new IllegalArgumentException("当前数据质量扫描仅支持 MySQL 数据源和 Doris；其他数据库可用于 ODS 同步及元数据采集");
        return transactions.execute(tx->{
            if(id==null) { input.setId(null); mapper.insert(input); }
            else { var t=editable(id,input.getVersion()); input.setId(t.getId()); mapper.update(input); mapper.deleteRules(id); }
            for(var rule:input.getRules()) { rule.setTaskId(input.getId()); mapper.rule(rule); }
            platform.audit(actor,"QUALITY_SAVE",input.getId().toString(),"SUCCESS"); return input.getId();
        });
    }
    public void schedule(long id,int version,boolean enabled,String actor) {
        transactions.executeWithoutResult(tx->{
            var t=editable(id,version);
            if(enabled && t.getCronExpression().isBlank()) throw new IllegalArgumentException("请先填写 Cron 再启用调度");
            mapper.schedule(id,enabled,enabled?next(t,now()):null);
            platform.audit(actor,enabled?"QUALITY_SCHEDULE":"QUALITY_PAUSE",Long.toString(id),"SUCCESS");
        });
    }
    public void archive(long id,int version,String actor) {
        transactions.executeWithoutResult(tx->{ editable(id,version); mapper.archive(id); platform.audit(actor,"QUALITY_ARCHIVE",Long.toString(id),"SUCCESS"); });
    }
    public long start(long id,int version,String actor) { return submit(claim(id,version,actor,false)); }
    private record Claimed(QualityTask task,QualityRun run) {}
    private Claimed claim(long id,Integer version,String actor,boolean dueOnly) {
        return transactions.execute(tx->{
            var t=required(mapper.lock(id)); var now=now();
            if(t.isArchived()) throw new IllegalArgumentException("任务已归档");
            if(version!=null && t.getVersion()!=version) throw new IllegalArgumentException("任务已变化，请刷新后重试");
            if(dueOnly && (!t.isEnabled() || t.getNextRunAt()==null || t.getNextRunAt().isAfter(now))) return null;
            if(t.getActiveRunId()!=null) { if(dueOnly) return null; throw new IllegalArgumentException("该任务正在扫描，请等待完成"); }
            t.setRules(mapper.rules(id)); validation.validate(t);
            var run=new QualityRun(); run.setTaskId(id); run.setTaskVersion(t.getVersion()); run.setActor(actor); run.setStartedAt(now); run.setStatus("RUNNING");
            run.setConfigSnapshot(json.writerWithDefaultPrettyPrinter().writeValueAsString(t)); mapper.insertRun(run);
            mapper.claim(id,run.getId(),t.isEnabled()?next(t,now):null);
            platform.audit(actor,"QUALITY_RUN",run.getId().toString(),"ACCEPTED"); return new Claimed(t,run);
        });
    }
    private long submit(Claimed claimed) {
        try { executor.execute(()->execute(claimed)); }
        catch(RejectedExecutionException e) { finish(claimed.run(),"ERROR","扫描队列已满或平台正在停止，请稍后重试"); }
        return claimed.run().getId();
    }
    private void execute(Claimed claimed) {
        var task=claimed.task(); var run=claimed.run(); String status="PASSED";
        try(var connection=scanner.open(task)) {
            connection.setReadOnly(true);
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
            for(var rule:task.getRules()) {
                QualityResult result;
                try {
                    if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new IllegalStateException("scan deadline");
                    BigDecimal baseline=rule.getType()==RuleType.ROW_COUNT_CHANGE?mapper.baseline(task.getId(),run.getTaskVersion(),run.getId(),rule.getName()):null;
                    result=scanner.scan(connection,task,rule,run.getStartedAt(),baseline);
                } catch(java.sql.SQLException | RuntimeException e) {
                    result=new QualityResult(); result.setName(rule.getName()); result.setType(rule.getType()); result.setSeverity(rule.getSeverity());
                    result.setAnomalyPercent(BigDecimal.ZERO); result.setStatus("ERROR"); result.setMessage("扫描未完成：请检查连接、表字段类型、参数及查询超时；驱动详情不回显");
                }
                result.setRunId(run.getId()); mapper.result(result);
                if(rank(result.getStatus())>rank(status)) status=result.getStatus();
            }
            finish(run,status,"扫描结束；请查看各规则结果");
        } catch(java.sql.SQLException | RuntimeException e) { finish(run,"ERROR","扫描执行失败，请检查数据源连接和查询权限；已完成的规则结果保留"); }
    }
    private void finish(QualityRun run,String status,String message) {
        transactions.executeWithoutResult(tx->{
            run.setStatus(status); run.setMessage(message); run.setFinishedAt(now()); mapper.finish(run); mapper.release(run.getTaskId(),run.getId());
            platform.audit(run.getActor(),"QUALITY_FINISH",run.getId().toString(),status);
        });
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // Single-instance deployment: interrupted JDBC work cannot resume after process restart.
        for(var run:mapper.unfinished()) finish(run,"ERROR","平台重启中断扫描，请重新运行");
        ready=true;
    }
    @Scheduled(fixedDelayString="${platform.quality.poll-ms:15000}")
    public void tick() {
        if(!ready || !scheduling) return;
        for(long id:mapper.due(now())) {
            try { var claim=claim(id,null,"scheduler",true); if(claim!=null) submit(claim); }
            catch(IllegalArgumentException | org.springframework.dao.TransientDataAccessException ignored) { /* Next tick retries a contested or changed task. */ }
        }
    }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
    private QualityTask editable(long id,int version) {
        var t=required(mapper.lock(id));
        if(t.isArchived()) throw new IllegalArgumentException("任务已归档");
        if(t.getVersion()!=version) throw new IllegalArgumentException("任务已变化，请刷新后重试");
        if(t.getActiveRunId()!=null) throw new IllegalArgumentException("任务正在扫描，请等待完成后修改");
        return t;
    }
    private static QualityTask required(QualityTask t) { if(t==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"质量任务不存在"); return t; }
    private static LocalDateTime now() { return LocalDateTime.now(QualityValidation.ZONE); }
    private static LocalDateTime next(QualityTask t,LocalDateTime now) { return CronExpression.parse(t.getCronExpression()).next(now); }
    private static int rank(String status) { return switch(status) { case "ERROR"->3; case "FAILED"->2; case "WARNING"->1; default->0; }; }
}
