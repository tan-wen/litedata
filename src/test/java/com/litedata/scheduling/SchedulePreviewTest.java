package com.litedata.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class SchedulePreviewTest {
    @Test void calculatesShanghaiBoundariesRatherThanHostTimezone() {
        Clock clock=Clock.fixed(Instant.parse("2026-10-24T17:59:59Z"),ZoneOffset.UTC);
        assertEquals(java.util.List.of("2026-10-25T02:00+08:00","2026-10-26T02:00+08:00","2026-10-27T02:00+08:00"),
                SchedulePreview.preview("0 0 2 * * ?",clock).nextTimes());
        assertEquals("2026-10-25T02:00+08:00",SchedulePreview.preview("0 */5 * * * ?",clock).nextTimes().getFirst());
        assertThrows(IllegalArgumentException.class,()->SchedulePreview.preview("* * * * * *",clock));
    }
    @Test void acceptsMinuteHourDayWeekAndCustomQuartzSchedulesButRejectsSecondLevelSchedules() {
        Clock clock=Clock.fixed(Instant.parse("2026-10-24T17:59:59Z"),ZoneOffset.UTC);
        for(String cron : java.util.List.of("0 */15 * * * ?", "0 0 */3 * * ?", "0 30 8 * * ?", "0 0 9 ? * MON-FRI"))
            assertEquals(3,SchedulePreview.preview(cron,clock).nextTimes().size());
        assertEquals("自定义 Cron（北京时间）",SchedulePreview.preview("0 0 9 ? * MON-FRI",clock).description());
        assertThrows(IllegalArgumentException.class,()->SchedulePreview.preview("*/5 * * * * ?",clock));
        assertThrows(IllegalArgumentException.class,()->SchedulePreview.preview("0 0 * * *",clock));
        assertThrows(IllegalArgumentException.class,()->SchedulePreview.preview("0 0 9 * * *",clock));
        assertThrows(IllegalArgumentException.class,()->SchedulePreview.preview("0 0 0 31 2 ?",clock));
        assertEquals("2026-10-26T09:00+08:00",SchedulePreview.preview("0 0 9 ? * 2",clock).nextTimes().getFirst());
    }
    @Test void suppressesSensitiveLinesAndConnectionStringsWithoutDestroyingUsefulProgress() {
        String text="INFO task started\n{\"password\":\"sample-secret\"}\nTOKEN=other-secret\njdbc:mysql://warehouse/db?password=third-secret\nconnect jdbc:mysql://warehouse/db\nINFO task completed\n";
        String safe=LogRedactor.redact(text);
        assertTrue(safe.contains("INFO task completed")); assertTrue(safe.contains("[敏感配置已隐藏]"));
        assertFalse(safe.contains("secret")); assertFalse(safe.contains("jdbc:mysql"));
        assertEquals(6,safe.lines().count());
    }
}
