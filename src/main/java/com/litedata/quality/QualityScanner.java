package com.litedata.quality;

import com.litedata.config.PlatformProperties;
import com.litedata.data.*;
import java.math.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Component;

/** Only aggregate results cross the JDBC boundary; business rows are never retained. */
@Component
public class QualityScanner {
    private final SourceService sources;
    private final PlatformProperties properties;
    private final JdbcConnections connections;
    public QualityScanner(SourceService sources,PlatformProperties properties,JdbcConnections connections) {
        this.sources=sources; this.properties=properties; this.connections=connections;
    }
    public Connection open(QualityTask task) throws SQLException {
        if(task.getSourceId()!=null) return sources.connect(sources.require(task.getSourceId()));
        var d=properties.doris(); return connections.open(d.jdbcUrl(),d.username(),d.password());
    }
    public QualityResult scan(Connection c,QualityTask t,QualityRule r,LocalDateTime now,BigDecimal baseline) throws SQLException {
        verifyColumns(c,t,r);
        var result=new QualityResult(); result.setName(r.getName()); result.setType(r.getType()); result.setSeverity(r.getSeverity());
        String table=quote(t.getTableName()), from=table+" q";
        List<Object> scopeParams=new ArrayList<>();
        String scope="1=1";
        if(!t.getTimeColumn().isEmpty()) {
            String time="q."+quote(t.getTimeColumn()); scope=time+">=?";
            scopeParams.add(now.minusHours(t.getWindowHours()));
            if(r.getType()!=RuleType.FUTURE_TIME) { scope+=" AND "+time+"<?"; scopeParams.add(now); }
        }
        long total=scalar(c,"SELECT COUNT(*) FROM "+from+" WHERE "+scope,scopeParams,t).longValueExact();
        long anomalies=0; String message="扫描完成"; BigDecimal metric=null;
        var type=r.getType();
        if(Set.of(RuleType.ROW_COUNT,RuleType.SUM_RANGE,RuleType.AVG_RANGE,RuleType.FRESHNESS,RuleType.ROW_COUNT_CHANGE).contains(type)) {
            if(type==RuleType.ROW_COUNT || type==RuleType.ROW_COUNT_CHANGE) metric=BigDecimal.valueOf(total);
            else if(type==RuleType.FRESHNESS) {
                try(var s=prepare(c,"SELECT MAX(q."+quote(QualityValidation.columns(r).getFirst())+") FROM "+from+" WHERE "+scope,scopeParams,t); var rs=s.executeQuery()) {
                    rs.next(); var stamp=rs.getObject(1,LocalDateTime.class);
                    if(stamp!=null) metric=BigDecimal.valueOf(java.time.Duration.between(stamp,now).toSeconds()).divide(new BigDecimal("3600"),8,RoundingMode.HALF_UP);
                }
            } else metric=scalar(c,"SELECT "+(type==RuleType.SUM_RANGE?"SUM":"AVG")+"(q."+quote(QualityValidation.columns(r).getFirst())+") FROM "+from+" WHERE "+scope,scopeParams,t);
            total=1;
            if(type==RuleType.ROW_COUNT_CHANGE) {
                if(baseline==null) message="待基线：首批仅记录数据量，下一次同版本扫描开始比较";
                else {
                    var change=baseline.signum()==0?(metric.signum()==0?BigDecimal.ZERO:null):metric.subtract(baseline).abs().multiply(new BigDecimal("100")).divide(baseline.abs(),8,RoundingMode.HALF_UP);
                    anomalies=change==null || change.compareTo(QualityValidation.decimal(r.getParameter1()))>0?1:0;
                    message="上次行数="+baseline.toPlainString()+"；本次行数="+metric.toPlainString()+"；变化="+(change==null?"从零增长":change.toPlainString()+"%");
                }
            } else if(type==RuleType.FRESHNESS) {
                anomalies=metric==null || metric.signum()<0 || metric.compareTo(QualityValidation.decimal(r.getParameter1()))>0?1:0;
                message=metric==null?"时间字段无有效数据":"最大时间距扫描时刻（小时）="+metric.toPlainString();
            } else {
                anomalies=metric==null || metric.compareTo(QualityValidation.decimal(r.getParameter1()))<0 || metric.compareTo(QualityValidation.decimal(r.getParameter2()))>0?1:0;
                message=metric==null?"聚合值为空":"检测值="+metric.toPlainString();
            }
        } else if(type==RuleType.CUSTOM_SQL) {
            anomalies=scalar(c,"SELECT COUNT(*) FROM ("+r.getParameter1()+") quality_custom",List.of(),t).longValueExact();
            total=Math.max(total,anomalies); message="自定义 SQL 返回行数代表异常数；分母为配置表行数与异常数的较大值";
        } else if(type==RuleType.UNIQUE) {
            String keys=String.join(",",QualityValidation.columns(r).stream().map(v->"q."+quote(v)).toList());
            anomalies=scalar(c,"SELECT COALESCE(SUM(n),0) FROM (SELECT COUNT(*) n FROM "+from+" WHERE "+scope+" GROUP BY "+keys+" HAVING COUNT(*)>1) quality_duplicates",scopeParams,t).longValueExact();
        } else {
            List<String> cols=QualityValidation.columns(r); String col="q."+quote(cols.getFirst());
            List<Object> params=new ArrayList<>(scopeParams); String bad;
            switch(type) {
                case NOT_NULL -> bad=String.join(" OR ",cols.stream().map(v->"q."+quote(v)+" IS NULL").toList());
                case NOT_BLANK -> bad=String.join(" OR ",cols.stream().map(v->"(q."+quote(v)+" IS NULL OR TRIM(q."+quote(v)+")='')").toList());
                case RANGE,LENGTH -> { String value=type==RuleType.LENGTH?"CHAR_LENGTH("+col+")":col; bad=value+"<? OR "+value+">?"; params.add(QualityValidation.decimal(r.getParameter1())); params.add(QualityValidation.decimal(r.getParameter2())); }
                case REGEX -> { bad=col+" NOT REGEXP ?"; params.add(r.getParameter1()); }
                case ENUM -> { var values=QualityValidation.values(r); bad=col+" NOT IN ("+String.join(",",Collections.nCopies(values.size(),"?"))+")"; params.addAll(values); }
                case NON_NEGATIVE -> bad=col+"<0";
                case POSITIVE -> bad=col+"<=0";
                case FUTURE_TIME -> { bad=col+">?"; params.add(now); }
                case COMPARE_COLUMNS -> bad="NOT ("+col+r.getParameter1()+"q."+quote(cols.get(1))+")";
                case REFERENCE -> bad=col+" IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "+quote(r.getReferenceTable())+" ref WHERE ref."+quote(r.getReferenceColumn())+"="+col+")";
                default -> throw new IllegalArgumentException("不支持的规则类型");
            }
            anomalies=scalar(c,"SELECT COUNT(*) FROM "+from+" WHERE "+scope+" AND ("+bad+")",params,t).longValueExact();
        }
        BigDecimal rate=total==0?BigDecimal.ZERO:BigDecimal.valueOf(anomalies).multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(total),4,RoundingMode.HALF_UP);
        // Compare the exact ratio so rounding cannot hide a small nonzero anomaly rate.
        boolean passed=anomalies<=r.getToleranceCount() && (total==0 || BigDecimal.valueOf(anomalies).multiply(new BigDecimal("100")).compareTo(r.getTolerancePercent().multiply(BigDecimal.valueOf(total)))<=0);
        String status=passed?"PASSED":("WARNING".equals(r.getSeverity())?"WARNING":"FAILED");
        if(type==RuleType.ROW_COUNT_CHANGE && baseline==null) status="WARNING";
        result.setTotalCount(total); result.setAnomalyCount(anomalies); result.setAnomalyPercent(rate); result.setStatus(status); result.setMessage(message); result.setMetricValue(metric);
        return result;
    }
    private static BigDecimal scalar(Connection c,String sql,List<?> params,QualityTask task) throws SQLException {
        try(var s=prepare(c,sql,params,task); var rs=s.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
    }
    private static PreparedStatement prepare(Connection c,String sql,List<?> params,QualityTask task) throws SQLException {
        var s=c.prepareStatement(sql);
        try { s.setQueryTimeout(task.getTimeoutSeconds()); s.setMaxRows(1); for(int i=0;i<params.size();i++) s.setObject(i+1,params.get(i)); return s; }
        catch(SQLException e) { s.close(); throw e; }
    }
    private static String quote(String identifier) { return "`"+JdbcConnections.identifier(identifier)+"`"; }
    private static void verifyColumns(Connection c,QualityTask task,QualityRule rule) throws SQLException {
        Map<String,Integer> types=new HashMap<>();
        try(var s=prepare(c,"SELECT * FROM "+quote(task.getTableName())+" WHERE 1=0",List.of(),task); var rs=s.executeQuery()) {
            var metadata=rs.getMetaData(); for(int i=1;i<=metadata.getColumnCount();i++) types.put(metadata.getColumnName(i).toLowerCase(Locale.ROOT),metadata.getColumnType(i));
        }
        if(!task.getTimeColumn().isEmpty()) checkType(types,task.getTimeColumn(),false,true);
        var type=rule.getType();
        if(Set.of(RuleType.ROW_COUNT,RuleType.ROW_COUNT_CHANGE,RuleType.CUSTOM_SQL).contains(type)) return;
        boolean numeric=Set.of(RuleType.RANGE,RuleType.NON_NEGATIVE,RuleType.POSITIVE,RuleType.SUM_RANGE,RuleType.AVG_RANGE).contains(type);
        boolean temporal=Set.of(RuleType.FUTURE_TIME,RuleType.FRESHNESS).contains(type);
        for(String col:QualityValidation.columns(rule)) checkType(types,col,numeric,temporal);
    }
    private static void checkType(Map<String,Integer> types,String col,boolean numeric,boolean temporal) {
        Integer type=types.get(col.toLowerCase(Locale.ROOT));
        if(type==null) throw new IllegalArgumentException("配置字段不存在");
        if(numeric && !Set.of(Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.DECIMAL,Types.NUMERIC,Types.DOUBLE,Types.FLOAT,Types.REAL).contains(type)) throw new IllegalArgumentException("数值规则须选择数值字段");
        if(temporal && !Set.of(Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE,Types.DATE).contains(type)) throw new IllegalArgumentException("时间规则须选择日期时间字段");
    }
}
