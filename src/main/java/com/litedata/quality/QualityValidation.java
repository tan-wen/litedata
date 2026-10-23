package com.litedata.quality;

import com.litedata.data.JdbcConnections;
import com.litedata.service.QueryPolicy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
public class QualityValidation {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final QueryPolicy policy;
    public QualityValidation(QueryPolicy policy) { this.policy=policy; }
    public void validate(QualityTask t) {
        t.setName(text(t.getName(),100,"任务名称",true)); t.setOwner(text(t.getOwner(),100,"负责人",true));
        t.setDescription(text(t.getDescription(),1000,"描述",false));
        t.setTableName(JdbcConnections.identifier(t.getTableName()));
        t.setTimeColumn(text(t.getTimeColumn(),64,"窗口时间字段",false));
        if(!t.getTimeColumn().isEmpty()) JdbcConnections.identifier(t.getTimeColumn());
        if(t.getWindowHours()<1 || t.getWindowHours()>8760) fail("时间窗口应为1到8760小时");
        if(t.getTimeoutSeconds()<1 || t.getTimeoutSeconds()>120) fail("单条查询超时应为1到120秒");
        t.setCronExpression(text(t.getCronExpression(),100,"Cron",false));
        if(!t.getCronExpression().isEmpty()) {
            try { if(CronExpression.parse(t.getCronExpression()).next(LocalDateTime.now(ZONE))==null) fail("Cron 无下一次执行时间"); }
            catch(IllegalArgumentException e) { fail("Cron 格式无效，使用6段 Spring Cron（秒 分 时 日 月 周）"); }
        }
        if(t.getRules()==null || t.getRules().isEmpty() || t.getRules().size()>30) fail("每个任务需要1到30条规则");
        Set<String> names=new HashSet<>();
        for(var r:t.getRules()) {
            if(r==null || r.getType()==null) fail("请选择规则类型");
            r.setName(text(r.getName(),100,"规则名称",true));
            if(!names.add(r.getName())) fail("同一任务的规则名称不能重复");
            r.setColumns(text(r.getColumns(),1000,"字段",false));
            r.setParameter1(text(r.getParameter1(),20000,"参数1",false)); r.setParameter2(text(r.getParameter2(),1000,"参数2",false));
            r.setReferenceTable(text(r.getReferenceTable(),64,"引用表",false)); r.setReferenceColumn(text(r.getReferenceColumn(),64,"引用字段",false));
            if(!Set.of("WARNING","CRITICAL").contains(Objects.toString(r.getSeverity(),""))) fail("异常等级无效");
            if(r.getToleranceCount()<0 || r.getTolerancePercent()==null || r.getTolerancePercent().signum()<0 || r.getTolerancePercent().compareTo(new BigDecimal("100"))>0 || r.getTolerancePercent().scale()>4) fail("容忍条数不能为负，容忍率应为0到100且最多4位小数");
            var type=r.getType();
            if(!Set.of(RuleType.ROW_COUNT,RuleType.ROW_COUNT_CHANGE,RuleType.CUSTOM_SQL).contains(type)) {
                var columns=columns(r);
                if(type==RuleType.COMPARE_COLUMNS && columns.size()!=2) fail("字段比较需要两个字段");
                if(!Set.of(RuleType.NOT_NULL,RuleType.NOT_BLANK,RuleType.UNIQUE,RuleType.COMPARE_COLUMNS).contains(type) && columns.size()!=1) fail("此规则需要一个字段");
            }
            switch(type) {
                case RANGE,LENGTH,ROW_COUNT,SUM_RANGE,AVG_RANGE -> {
                    var min=decimal(r.getParameter1()); var max=decimal(r.getParameter2());
                    if(min.compareTo(max)>0) fail("最小值不能大于最大值");
                    if(type==RuleType.LENGTH || type==RuleType.ROW_COUNT) {
                        if(min.signum()<0 || min.stripTrailingZeros().scale()>0 || max.stripTrailingZeros().scale()>0) fail("长度及行数范围必须为非负整数");
                    }
                }
                case FRESHNESS,ROW_COUNT_CHANGE -> { if(decimal(r.getParameter1()).signum()<0) fail("延迟或波动阈值不能为负"); }
                case ENUM -> { if(values(r).isEmpty() || values(r).size()>200) fail("枚举需1到200项，每行一个"); }
                case REGEX -> { if(r.getParameter1().isBlank() || r.getParameter1().length()>1000) fail("正则不能为空，最长1000字符"); }
                case COMPARE_COLUMNS -> { if(!Set.of("=","<>",">",">=","<","<=").contains(r.getParameter1())) fail("请选择有效比较关系"); }
                case REFERENCE -> { JdbcConnections.identifier(r.getReferenceTable()); JdbcConnections.identifier(r.getReferenceColumn()); }
                case CUSTOM_SQL -> {
                    if(t.getSourceId()!=null || !t.getTimeColumn().isEmpty()) fail("自定义 SQL 仅允许 Doris，时间过滤请写在 SQL 内");
                    if(!policy.validate(r.getParameter1()).parameters().isEmpty()) fail("质量 SQL 不支持命名参数");
                }
                default -> { }
            }
        }
    }
    public static List<String> columns(QualityRule r) {
        var list=Arrays.stream(r.getColumns().split(",",-1)).map(String::trim).map(JdbcConnections::identifier).toList();
        if(list.size()>10 || new HashSet<>(list).size()!=list.size()) fail("字段最多10个且不能重复");
        return list;
    }
    public static List<String> values(QualityRule r) { return r.getParameter1().lines().map(String::trim).filter(s->!s.isEmpty()).distinct().toList(); }
    public static BigDecimal decimal(String value) {
        try { var n=new BigDecimal(value); if(n.precision()>30 || Math.abs((long)n.scale())>8) fail("数值最多30位，最多8位小数"); return n; }
        catch(NumberFormatException e) { throw new IllegalArgumentException("请填写有效数值参数"); }
    }
    private static String text(String value,int max,String label,boolean required) {
        String s=Objects.toString(value,"").trim(); if(s.length()>max || (required && s.isEmpty())) fail(label+"不能为空且不能超过"+max+"字符"); return s;
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
