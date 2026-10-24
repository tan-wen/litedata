package com.litedata.metric;

import java.time.LocalDateTime;

public record MetricVersion(long metricId, int version, String actor, LocalDateTime createdAt,
                            String definitionJson, String sqlText) {}
