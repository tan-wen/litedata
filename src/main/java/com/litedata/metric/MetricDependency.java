package com.litedata.metric;

/** The version used when the downstream definition was saved. */
public record MetricDependency(Long id, String code, String name, MetricType type, int version) {}
