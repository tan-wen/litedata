package com.litedata.catalog;

import com.litedata.metric.MetricType;

import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record AssetFilters(String keyword, String assetType, String subjectArea, String owner, String tag,
                           String status, String assetCategory) {
    public static final List<String> MODEL_LAYERS = List.of("ODS", "DIM", "DWD", "DWS", "ADS");

    public static AssetFilters from(Map<String, String> params) {
        String type = text(params, "assetType", 16), status = text(params, "assetStatus", 16);
        String category = text(params, "assetCategory", 105);
        if (!type.isEmpty() && Arrays.stream(AssetType.values()).noneMatch(t -> t.name().equals(type)))
            throw bad("资产类型无效");
        if (!status.isEmpty() && !AssetCatalogService.STATUSES.containsKey(status)) throw bad("资产状态无效");
        if (!category.isEmpty()) {
            boolean valid = switch (type) {
                case "TABLE" -> category.equals("doris") || category.matches("source:[1-9][0-9]{0,18}");
                case "MODEL" -> MODEL_LAYERS.contains(category);
                case "METRIC" -> Arrays.stream(MetricType.values()).anyMatch(t -> t.name().equals(category));
                case "SERVICE" -> category.equals("unclassified") || (category.startsWith("area:") && !category.substring(5).isBlank());
                default -> false;
            };
            if (!valid) throw bad("资产分类无效");
        }
        return new AssetFilters(text(params, "keyword", 100).toLowerCase(Locale.ROOT), type,
            text(params, "subjectArea", 100), text(params, "owner", 100), text(params, "tag", 50), status, category);
    }

    private static String text(Map<String, String> params, String name, int limit) {
        String value = Objects.toString(params.get(name), "").trim();
        if (value.length() > limit) throw bad("筛选条件 " + name + " 最长 " + limit + " 字符");
        return value;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
