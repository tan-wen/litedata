package com.litedata.web;

import java.time.*;

final class DisplayTime {
    private DisplayTime() {
    }

    static LocalDateTime beijing(LocalDateTime utc) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
    }
}
