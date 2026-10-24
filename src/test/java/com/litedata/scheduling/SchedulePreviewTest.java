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
    @Test void suppressesSensitiveLinesAndConnectionStringsWithoutDestroyingUsefulProgress() {
        String text="INFO task started\n{\"password\":\"sample-secret\"}\nTOKEN=other-secret\njdbc:mysql://warehouse/db?password=third-secret\nconnect jdbc:mysql://warehouse/db\nINFO task completed\n";
        String safe=LogRedactor.redact(text);
        assertTrue(safe.contains("INFO task completed")); assertTrue(safe.contains("[敏感配置已隐藏]"));
        assertFalse(safe.contains("secret")); assertFalse(safe.contains("jdbc:mysql"));
        assertEquals(6,safe.lines().count());
    }
}
