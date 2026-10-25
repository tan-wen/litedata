package com.litedata.scheduling;

import java.time.*;
import java.util.*;
import org.quartz.CronExpression;

/** Previews Quartz schedules accepted by the platform. */
public final class SchedulePreview {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final Map<String,String> POLICIES=Map.of(
            "0 0 * * * ?","每小时整点", "0 */5 * * * ?","每 5 分钟", "0 0 2 * * ?","每天 02:00");
    private SchedulePreview() {}
    public record Preview(String cron,String timezone,String description,List<String> nextTimes) {}
    public static Preview preview(String cron,Clock clock) {
        String normalized=validate(cron);
        String description=POLICIES.getOrDefault(normalized,"自定义 Cron（北京时间）");
        var expression=expression(normalized);
        Date next=Date.from(ZonedDateTime.now(clock).withZoneSameInstant(ZONE).toInstant());
        List<String> times=new ArrayList<>();
        for(int i=0;i<3;i++) {
            next=expression.getNextValidTimeAfter(next);
            if(next==null) throw new IllegalArgumentException("Cron 没有可预览的未来触发时间");
            times.add(next.toInstant().atZone(ZONE).toOffsetDateTime().toString());
        }
        return new Preview(normalized,ZONE.getId(),description,List.copyOf(times));
    }
    public static String validate(String cron) {
        if(cron==null || cron.isBlank() || cron.length()>100) throw new IllegalArgumentException("Cron 表达式不能为空，最长100字符");
        String normalized=cron.trim().replaceAll("\\s+"," ");
        String[] fields=normalized.split(" ");
        if(fields.length<6 || fields.length>7) throw new IllegalArgumentException("Cron 必须使用 6 或 7 段 Quartz 格式");
        if(!"0".equals(fields[0])) throw new IllegalArgumentException("同步管道最小执行频率为每分钟，秒字段必须为 0");
        if(("?".equals(fields[3])) == ("?".equals(fields[5])))
            throw new IllegalArgumentException("日期与星期字段必须恰有一个为 ?");
        var expression=expression(normalized);
        if(expression.getNextValidTimeAfter(new Date())==null) throw new IllegalArgumentException("Cron 没有可预览的未来触发时间");
        return normalized;
    }
    private static CronExpression expression(String cron) {
        try {
            var expression=new CronExpression(cron);
            expression.setTimeZone(TimeZone.getTimeZone(ZONE));
            return expression;
        } catch(java.text.ParseException exception) { throw new IllegalArgumentException("Cron 表达式无效"); }
    }
}
