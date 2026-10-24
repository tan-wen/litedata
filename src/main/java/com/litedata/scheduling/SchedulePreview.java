package com.litedata.scheduling;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;

/** Preview only the policies actually accepted by the task forms and executors. */
public final class SchedulePreview {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final Map<String,String> POLICIES=Map.of(
            "0 0 * * * ?","每小时整点", "0 */5 * * * ?","每 5 分钟", "0 0 2 * * ?","每天 02:00");
    private SchedulePreview() {}
    public record Preview(String cron,String timezone,String description,List<String> nextTimes) {}
    public static Preview preview(String cron,Clock clock) {
        String description=POLICIES.get(cron);
        if(description==null) throw new IllegalArgumentException("请选择支持的调度周期");
        var expression=CronExpression.parse(cron);
        var next=ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        List<String> times=new ArrayList<>();
        for(int i=0;i<3;i++) { next=expression.next(next); times.add(next.toOffsetDateTime().toString()); }
        return new Preview(cron,ZONE.getId(),description,List.copyOf(times));
    }
}
