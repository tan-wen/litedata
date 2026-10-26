"""Deterministic, offline fixture for the chemical-park DIM example.

Decimal-valued business measures are rendered as strings in JSON.  This avoids
silently changing a DECIMAL(18,3) value into a binary floating-point value;
the SQL fixture still inserts them as numeric literals.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any


PREFIX_RE = re.compile(r"cpdim_[a-z0-9]{6,12}\Z")
PRODUCT = "RESIN_A"


def _identifier(prefix: str, suffix: str) -> str:
    if not PREFIX_RE.fullmatch(prefix):
        raise ValueError("prefix must match cpdim_[a-z0-9]{6,12}")
    value = f"{prefix}_{suffix}"
    if len(value) >= 64:
        raise ValueError("generated identifier must be shorter than 64 characters")
    return value


def _decimal(value: Decimal, places: str = "0.001") -> str:
    return format(value.quantize(Decimal(places), rounding=ROUND_HALF_UP), "f")


def _json_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return _decimal(value)
    if isinstance(value, (dt.date, dt.datetime)):
        return value.isoformat(sep=" ") if isinstance(value, dt.datetime) else value.isoformat()
    if isinstance(value, dict):
        return {key: _json_value(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_json_value(item) for item in value]
    return value


def _fields(*items: tuple[str, str, bool, bool]) -> list[dict[str, Any]]:
    return [{"name": n, "type": t, "primaryKey": pk, "indexed": indexed}
            for n, t, pk, indexed in items]


def _model_fields(columns: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Shape fields for the modelling API (not the source-table description)."""
    return [{"name": col["name"], "dataType": col["type"],
             "keyColumn": col["primaryKey"], "nullable": False,
             "comment": "", "standard": ""} for col in columns]


def _seed_sql(tables: dict[str, str], enterprises: list[dict[str, Any]],
              utility: list[dict[str, Any]], production: list[dict[str, Any]]) -> str:
    statements = [
        f"CREATE TABLE `{tables['enterprise']}` (enterprise_id BIGINT NOT NULL, enterprise_name VARCHAR(80) NOT NULL, product_code VARCHAR(32) NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY (enterprise_id), KEY idx_updated_at (updated_at))",
        f"CREATE TABLE `{tables['utility']}` (record_id BIGINT NOT NULL, enterprise_id BIGINT NOT NULL, stat_date DATE NOT NULL, period_end DATETIME NOT NULL, steam_tons DECIMAL(18,3) NOT NULL, electricity_kwh DECIMAL(18,3) NOT NULL, water_m3 DECIMAL(18,3) NOT NULL, is_valid INT NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY (record_id), UNIQUE KEY uk_enterprise_stat_date (enterprise_id, stat_date), KEY idx_updated_at (updated_at))",
        f"CREATE TABLE `{tables['production']}` (record_id BIGINT NOT NULL, enterprise_id BIGINT NOT NULL, stat_date DATE NOT NULL, production_tons DECIMAL(18,3) NOT NULL, is_valid INT NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY (record_id), UNIQUE KEY uk_enterprise_stat_date (enterprise_id, stat_date), KEY idx_updated_at (updated_at))",
    ]
    values = ", ".join("(%d, '%s', '%s', NOW(6)-INTERVAL 1 MINUTE)" %
                       (r["enterprise_id"], r["enterprise_name"], r["product_code"])
                       for r in enterprises)
    statements.append(f"INSERT INTO `{tables['enterprise']}` (enterprise_id, enterprise_name, product_code, updated_at) VALUES {values}")
    values = ", ".join("(%d, %d, '%s', '%s', %s, %s, %s, 1, NOW(6)-INTERVAL 1 MINUTE)" %
                       (r["record_id"], r["enterprise_id"], r["stat_date"], r["period_end"],
                        _decimal(r["steam_tons"]), _decimal(r["electricity_kwh"]), _decimal(r["water_m3"]))
                       for r in utility)
    statements.append(f"INSERT INTO `{tables['utility']}` (record_id, enterprise_id, stat_date, period_end, steam_tons, electricity_kwh, water_m3, is_valid, updated_at) VALUES {values}")
    values = ", ".join("(%d, %d, '%s', %s, 1, NOW(6)-INTERVAL 1 MINUTE)" %
                       (r["record_id"], r["enterprise_id"], r["stat_date"], _decimal(r["production_tons"]))
                       for r in production)
    statements.append(f"INSERT INTO `{tables['production']}` (record_id, enterprise_id, stat_date, production_tons, is_valid, updated_at) VALUES {values}")
    return ";\n".join(statements) + ";\n"


def build_scenario(prefix: str, as_of: dt.date) -> dict[str, Any]:
    """Build a deterministic manifest for an eight-day, three-enterprise park."""
    if not isinstance(as_of, dt.date) or isinstance(as_of, dt.datetime):
        raise TypeError("as_of must be a datetime.date")
    _identifier(prefix, "x")
    tables = {key: _identifier(prefix, key) for key in ("enterprise", "utility", "production")}
    models = {key: _identifier(prefix, key) for key in ("ods_enterprise", "ods_utility", "ods_production", "dim_enterprise", "dwd_utility_day", "dws_enterprise_7d", "ads_park_7d")}
    enterprises = [
        {"enterprise_id": 1, "enterprise_name": "虚构华东树脂厂", "product_code": PRODUCT},
        {"enterprise_id": 2, "enterprise_name": "虚构滨江树脂厂", "product_code": PRODUCT},
        {"enterprise_id": 3, "enterprise_name": "虚构新城树脂厂", "product_code": PRODUCT},
    ]
    utility, production = [], []
    for d in range(1, 9):
        stat_date = as_of - dt.timedelta(days=d)
        period_end = dt.datetime.combine(stat_date + dt.timedelta(days=1), dt.time())
        for enterprise_id in (1, 2, 3):
            if enterprise_id == 1:
                p, steam, electricity, water = Decimal(100 + 2*d), Decimal("1.5"), Decimal(80), Decimal(3)
            elif enterprise_id == 2:
                p, steam, electricity, water = Decimal(80 + d), Decimal(2), Decimal(110), Decimal("3.5")
            else:
                p = Decimal(0 if d == 1 else 60 + d)
                steam, electricity, water = Decimal("1.2"), Decimal(90), Decimal("2.8")
            if enterprise_id == 3 and d == 1:
                steam_value, electricity_value, water_value = Decimal(10), Decimal(500), Decimal(5)
            else:
                steam_value, electricity_value, water_value = steam*p, electricity*p, water*p
            if enterprise_id == 2 and d == 8:
                steam_value = Decimal(-5)
            record_id = (d - 1) * 3 + enterprise_id
            utility.append({"record_id": record_id, "enterprise_id": enterprise_id, "stat_date": stat_date,
                            "period_end": period_end, "steam_tons": steam_value, "electricity_kwh": electricity_value,
                            "water_m3": water_value, "is_valid": 1})
            production.append({"record_id": record_id, "enterprise_id": enterprise_id, "stat_date": stat_date,
                               "production_tons": p, "is_valid": 1})

    source_columns = {
        "enterprise": _fields(("enterprise_id", "BIGINT", True, False), ("enterprise_name", "VARCHAR(80)", False, False), ("product_code", "VARCHAR(32)", False, False), ("updated_at", "DATETIME(6)", False, True)),
        "utility": _fields(("record_id", "BIGINT", True, False), ("enterprise_id", "BIGINT", False, False), ("stat_date", "DATE", False, False), ("period_end", "DATETIME", False, False), ("steam_tons", "DECIMAL(18,3)", False, False), ("electricity_kwh", "DECIMAL(18,3)", False, False), ("water_m3", "DECIMAL(18,3)", False, False), ("is_valid", "INT", False, False), ("updated_at", "DATETIME(6)", False, True)),
        "production": _fields(("record_id", "BIGINT", True, False), ("enterprise_id", "BIGINT", False, False), ("stat_date", "DATE", False, False), ("production_tons", "DECIMAL(18,3)", False, False), ("is_valid", "INT", False, False), ("updated_at", "DATETIME(6)", False, True)),
    }
    dwd_fields = _fields(("record_id", "BIGINT", True, False), ("enterprise_id", "BIGINT", False, False), ("enterprise_name", "VARCHAR(80)", False, False), ("product_code", "VARCHAR(32)", False, False), ("stat_date", "DATE", False, False), ("period_end", "DATETIME", False, False), ("steam_tons", "DECIMAL(18,3)", False, False), ("electricity_kwh", "DECIMAL(18,3)", False, False), ("water_m3", "DECIMAL(18,3)", False, False), ("production_tons", "DECIMAL(18,3)", False, False), ("updated_at", "DATETIME(6)", False, False))
    dws_fields = _fields(("enterprise_id", "BIGINT", True, False), ("enterprise_name", "VARCHAR(80)", False, False), ("product_code", "VARCHAR(32)", False, False), ("valid_days", "BIGINT", False, False), ("steam_tons", "DECIMAL(18,3)", False, False), ("electricity_kwh", "DECIMAL(18,3)", False, False), ("water_m3", "DECIMAL(18,3)", False, False), ("production_tons", "DECIMAL(18,3)", False, False), ("steam_per_ton", "DECIMAL(18,6)", False, False), ("kwh_per_ton", "DECIMAL(18,6)", False, False), ("updated_at", "DATETIME(6)", False, False))
    ads_fields = _fields(("park_id", "BIGINT", True, False), ("as_of_date", "DATE", False, False), ("enterprise_count", "BIGINT", False, False), ("valid_days", "BIGINT", False, False), ("steam_tons", "DECIMAL(18,3)", False, False), ("electricity_kwh", "DECIMAL(18,3)", False, False), ("water_m3", "DECIMAL(18,3)", False, False), ("production_tons", "DECIMAL(18,3)", False, False), ("steam_per_ton", "DECIMAL(18,6)", False, False), ("kwh_per_ton", "DECIMAL(18,6)", False, False), ("updated_at", "DATETIME(6)", False, False))
    # ``as_of`` is the warehouse-clock date: transformations intentionally use
    # CURRENT_DATE, so the runner must execute against the same warehouse date.
    manifest: dict[str, Any] = {"example": "chemical-park-dim", "prefix": prefix, "as_of": as_of,
    "metadata": {"asOfConvention": "warehouse CURRENT_DATE", "periodFields": ["stat_date", "period_end"],
                 "csvFields": {"stat_date": "date", "period_end": "datetime"},
                 "seedDatetimeLiterals": True}, "source_tables": [
        {"key": "enterprise", "tableName": tables["enterprise"], "columns": source_columns["enterprise"], "rows": enterprises},
        {"key": "utility", "tableName": tables["utility"], "columns": source_columns["utility"], "rows": utility},
        {"key": "production", "tableName": tables["production"], "columns": source_columns["production"], "rows": production},
    ], "seed_sql": _seed_sql(tables, enterprises, utility, production),
    "correction_sql": f"UPDATE `{tables['utility']}` SET steam_tons = 176.000, updated_at = NOW(6)-INTERVAL 1 SECOND WHERE enterprise_id = 2 AND stat_date = '{(as_of - dt.timedelta(days=8)).isoformat()}'",
    "models": [
        *[{"key": f"ods_{key}", "tableName": models[f"ods_{key}"], "name": f"ODS {key}", "subjectArea": "chemical_park", "layer": "ODS", "buckets": 1, "fields": _model_fields(source_columns[key])} for key in ("enterprise", "utility", "production")],
        {"key": "dim_enterprise", "tableName": models["dim_enterprise"], "name": "DIM 公共企业维度", "subjectArea": "chemical_park", "layer": "DIM", "buckets": 1, "dimensionKeys": "enterprise_id", "dimensionLabel": "enterprise_name", "dimensionStrategy": "SCD1", "fields": _model_fields(source_columns["enterprise"])},
        {"key": "dwd_utility_day", "tableName": models["dwd_utility_day"], "name": "DWD 日能耗产量", "subjectArea": "chemical_park", "layer": "DWD", "buckets": 1, "fields": _model_fields(dwd_fields)},
        {"key": "dws_enterprise_7d", "tableName": models["dws_enterprise_7d"], "name": "DWS 企业七日", "subjectArea": "chemical_park", "layer": "DWS", "buckets": 1, "fields": _model_fields(dws_fields)},
        {"key": "ads_park_7d", "tableName": models["ads_park_7d"], "name": "ADS 园区七日", "subjectArea": "chemical_park", "layer": "ADS", "buckets": 1, "fields": _model_fields(ads_fields)},
    ],
    "pipelines": [{"key": key, "name": f"同步 {key}", "sourceTable": tables[key], "watermarkColumn": "updated_at", "targetModel": f"ods_{key}", "cronExpression": "0 0 * * * ?"} for key in ("enterprise", "utility", "production")],
    "transforms": [
        {"key": "dim_enterprise", "name": "公共企业维度", "targetModel": "dim_enterprise", "upstreamPipelines": ["enterprise"], "upstreamTasks": [], "cronExpression": "0 0 2 * * ?", "sqlText": f"INSERT INTO {models['dim_enterprise']} (enterprise_id, enterprise_name, product_code, updated_at) SELECT enterprise_id, enterprise_name, product_code, updated_at FROM {models['ods_enterprise']}"},
        {"key": "dwd_utility_day", "name": "日能耗产量明细", "targetModel": "dwd_utility_day", "upstreamPipelines": ["utility", "production"], "upstreamTasks": ["dim_enterprise"], "cronExpression": "0 0 2 * * ?", "sqlText": f"INSERT INTO {models['dwd_utility_day']} (record_id, enterprise_id, enterprise_name, product_code, stat_date, period_end, steam_tons, electricity_kwh, water_m3, production_tons, updated_at) SELECT u.record_id, e.enterprise_id, e.enterprise_name, e.product_code, u.stat_date, u.period_end, u.steam_tons, u.electricity_kwh, u.water_m3, p.production_tons, u.updated_at FROM {models['ods_utility']} u JOIN {models['ods_production']} p ON u.enterprise_id = p.enterprise_id AND u.stat_date = p.stat_date JOIN {models['dim_enterprise']} e ON u.enterprise_id = e.enterprise_id WHERE u.is_valid = 1 AND p.is_valid = 1 AND u.steam_tons >= 0 AND u.electricity_kwh >= 0 AND u.water_m3 >= 0 AND p.production_tons >= 0 AND e.product_code = 'RESIN_A'"},
        {"key": "dws_enterprise_7d", "name": "企业七日汇总", "targetModel": "dws_enterprise_7d", "upstreamPipelines": [], "upstreamTasks": ["dwd_utility_day"], "cronExpression": "0 0 2 * * ?", "sqlText": f"INSERT INTO {models['dws_enterprise_7d']} (enterprise_id, enterprise_name, product_code, valid_days, steam_tons, electricity_kwh, water_m3, production_tons, steam_per_ton, kwh_per_ton, updated_at) SELECT enterprise_id, enterprise_name, product_code, COUNT(*), SUM(steam_tons), SUM(electricity_kwh), SUM(water_m3), SUM(production_tons), SUM(steam_tons) / NULLIF(SUM(production_tons), 0), SUM(electricity_kwh) / NULLIF(SUM(production_tons), 0), NOW(6) FROM {models['dwd_utility_day']} WHERE stat_date >= DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY) AND stat_date < CURRENT_DATE GROUP BY enterprise_id, enterprise_name, product_code"},
        {"key": "ads_park_7d", "name": "园区七日看板", "targetModel": "ads_park_7d", "upstreamPipelines": [], "upstreamTasks": ["dws_enterprise_7d"], "cronExpression": "0 0 2 * * ?", "sqlText": f"INSERT INTO {models['ads_park_7d']} (park_id, as_of_date, enterprise_count, valid_days, steam_tons, electricity_kwh, water_m3, production_tons, steam_per_ton, kwh_per_ton, updated_at) SELECT 1, CURRENT_DATE, COUNT(*), SUM(valid_days), SUM(steam_tons), SUM(electricity_kwh), SUM(water_m3), SUM(production_tons), SUM(steam_tons) / NULLIF(SUM(production_tons), 0), SUM(electricity_kwh) / NULLIF(SUM(production_tons), 0), NOW(6) FROM {models['dws_enterprise_7d']}"},
    ]}
    measures = [("steam", "steam_tons", "蒸汽用量", "吨"), ("electricity", "electricity_kwh", "电量", "kWh"), ("water", "water_m3", "用水量", "m³"), ("production", "production_tons", "产量", "吨")]
    metrics = []
    for key, column, name, unit in measures:
        metrics.append({"key": f"metric_{key}_daily", "code": f"{prefix}_{key}_daily", "name": name, "type": "ATOMIC", "subjectArea": "chemical_park", "owner": "demo", "unit": unit, "description": f"企业{name}合计", "dimensions": ["enterprise_id"], "aggregation": "SUM", "measureColumn": column, "timeColumn": "period_end", "windowDays": 1, "filterExpression": "", "formula": "", "modelKey": "dwd_utility_day"})
        metrics.append({"key": f"metric_{key}_7d", "code": f"{prefix}_{key}_7d", "name": f"七日{name}", "type": "DERIVED", "subjectArea": "chemical_park", "owner": "demo", "unit": unit, "description": f"RESIN_A 企业近七日{name}", "dimensions": ["enterprise_id"], "aggregation": "SUM", "measureColumn": column, "timeColumn": "period_end", "windowDays": 7, "filterExpression": "product_code = 'RESIN_A'", "formula": "", "modelKey": "dwd_utility_day", "baseMetricKey": f"metric_{key}_daily"})
    metrics += [
        {"key": "metric_steam_per_ton_7d", "code": f"{prefix}_steam_per_ton_7d", "name": "七日吨产品蒸汽", "type": "COMPOSITE", "subjectArea": "chemical_park", "owner": "demo", "unit": "吨/吨", "description": "七日蒸汽除以产量", "dimensions": ["enterprise_id"], "aggregation": "", "measureColumn": "", "timeColumn": "period_end", "windowDays": 7, "filterExpression": "product_code = 'RESIN_A'", "formula": f"[{prefix}_steam_7d]/[{prefix}_production_7d]", "baseMetricKey": "metric_steam_7d"},
        {"key": "metric_kwh_per_ton_7d", "code": f"{prefix}_kwh_per_ton_7d", "name": "七日吨产品电耗", "type": "COMPOSITE", "subjectArea": "chemical_park", "owner": "demo", "unit": "kWh/吨", "description": "七日电力除以产量", "dimensions": ["enterprise_id"], "aggregation": "", "measureColumn": "", "timeColumn": "period_end", "windowDays": 7, "filterExpression": "product_code = 'RESIN_A'", "formula": f"[{prefix}_electricity_7d]/[{prefix}_production_7d]", "baseMetricKey": "metric_electricity_7d"},
    ]
    manifest["metrics"] = metrics
    def rule(name: str, kind: str, columns: str, parameter1: str = "", parameter2: str = "") -> dict[str, Any]:
        return {"name": name, "type": kind, "columns": columns, "parameter1": parameter1,
                "parameter2": parameter2, "referenceTable": "", "referenceColumn": "",
                "toleranceCount": 0, "tolerancePercent": 0, "severity": "CRITICAL"}
    reference = rule("企业维度引用", "REFERENCE", "enterprise_id")
    reference.update(referenceTable=models["dim_enterprise"], referenceColumn="enterprise_id")
    manifest["qualities"] = [
        {"key": "dim_enterprise_quality", "name": "公共企业维度", "sourceKind": "warehouse", "tableName": models["dim_enterprise"], "owner": "demo", "description": "成员业务键唯一、名称非空且恰好三行", "rules": [rule("业务键唯一", "UNIQUE", "enterprise_id"), rule("业务键非空", "NOT_NULL", "enterprise_id"), rule("名称非空白", "NOT_BLANK", "enterprise_name"), rule("成员数", "ROW_COUNT", "", "3", "3")], "expectedStatus": "PASSED"},
        {"key": "source_utility_quality", "name": "源端蒸汽数据", "sourceKind": "source", "tableName": tables["utility"], "owner": "demo", "description": "纠正前有一条负蒸汽", "rules": [rule("蒸汽非负", "NON_NEGATIVE", "steam_tons"), rule("企业日期唯一", "UNIQUE", "enterprise_id,stat_date"), rule("行数", "ROW_COUNT", "", "24", "24")], "expectedStatus": "FAILED"},
        {"key": "dwd_utility_quality", "name": "仓库日明细", "sourceKind": "warehouse", "tableName": models["dwd_utility_day"], "owner": "demo", "description": "纠正前过滤异常，纠正后全量", "rules": [reference, rule("蒸汽非负", "NON_NEGATIVE", "steam_tons"), rule("电量非负", "NON_NEGATIVE", "electricity_kwh"), rule("用水非负", "NON_NEGATIVE", "water_m3"), rule("产量非负", "NON_NEGATIVE", "production_tons"), rule("企业日期唯一", "UNIQUE", "enterprise_id,stat_date"), rule("行数范围", "ROW_COUNT", "", "23", "24")], "expectedStatus": "PASSED"},
    ]
    slug_prefix = prefix.replace("_", "-")
    manifest["services"] = [
        {"key": "park_7d", "name": "园区七日", "slug": f"{slug_prefix}-park-7d", "querySql": f"SELECT * FROM {models['ads_park_7d']}"},
        {"key": "enterprise_7d", "name": "企业七日", "slug": f"{slug_prefix}-enterprise-7d", "querySql": f"SELECT * FROM {models['dws_enterprise_7d']} ORDER BY enterprise_id"},
        {"key": "daily_trace", "name": "企业日追溯", "slug": f"{slug_prefix}-daily-trace", "params": ["enterpriseId", "statDate"], "querySql": f"SELECT *, steam_tons / NULLIF(production_tons, 0) AS steam_per_ton, electricity_kwh / NULLIF(production_tons, 0) AS kwh_per_ton FROM {models['dwd_utility_day']} WHERE enterprise_id = :enterpriseId AND stat_date = :statDate"},
        {"key": "daily_all", "name": "日明细", "slug": f"{slug_prefix}-daily", "querySql": f"SELECT * FROM {models['dwd_utility_day']} ORDER BY stat_date, enterprise_id"},
        {"key": "metric_steam_7d_service", "name": "吨产品蒸汽指标", "slug": f"{slug_prefix}-metric-steam", "metricKey": "metric_steam_per_ton_7d", "params": []},
    ]
    production_by_key = {(row["enterprise_id"], row["stat_date"]): row for row in production}
    dwd_initial = [row for row in utility if row["is_valid"] == 1 and row["steam_tons"] >= 0 and row["electricity_kwh"] >= 0 and row["water_m3"] >= 0 and production_by_key[(row["enterprise_id"], row["stat_date"])]["production_tons"] >= 0]
    # The second chain run corrects the one deliberately invalid source row.
    # Keep an explicit final DWD projection so API verification is independent
    # of the SQL task's own result set and aggregation.
    corrected_utility = []
    for row in utility:
        corrected = dict(row)
        if row["enterprise_id"] == 2 and row["stat_date"] == as_of - dt.timedelta(days=8):
            corrected["steam_tons"] = Decimal("176.000")
        corrected_utility.append(corrected)
    dwd_final_rows = []
    enterprise_by_id = {row["enterprise_id"]: row for row in enterprises}
    for row in corrected_utility:
        production_row = production_by_key[(row["enterprise_id"], row["stat_date"])]
        if row["is_valid"] != 1 or production_row["is_valid"] != 1:
            continue
        if any(row[field] < 0 for field in ("steam_tons", "electricity_kwh", "water_m3")) or production_row["production_tons"] < 0:
            continue
        enterprise = enterprise_by_id[row["enterprise_id"]]
        dwd_final_rows.append({
            "record_id": row["record_id"], "enterprise_id": row["enterprise_id"],
            "enterprise_name": enterprise["enterprise_name"], "product_code": enterprise["product_code"],
            "stat_date": row["stat_date"], "period_end": row["period_end"],
            "steam_tons": row["steam_tons"], "electricity_kwh": row["electricity_kwh"],
            "water_m3": row["water_m3"], "production_tons": production_row["production_tons"],
        })
    dwd_final_rows.sort(key=lambda row: row["record_id"])
    seven_days = [row for row in dwd_initial if as_of - dt.timedelta(days=7) <= row["stat_date"] < as_of]
    measure_names = ("steam_tons", "electricity_kwh", "water_m3")
    enterprise7d = []
    for enterprise_id in sorted(enterprise_by_id):
        rows = [row for row in seven_days if row["enterprise_id"] == enterprise_id]
        total = {name: sum((row[name] for row in rows), Decimal(0)) for name in measure_names}
        total["production_tons"] = sum((production_by_key[(row["enterprise_id"], row["stat_date"])]["production_tons"] for row in rows), Decimal(0))
        enterprise7d.append({"enterprise_id": enterprise_id, "enterprise_name": enterprise_by_id[enterprise_id]["enterprise_name"], "product_code": PRODUCT, "valid_days": len(rows), **total, "steam_per_ton": None if not total["production_tons"] else _decimal(total["steam_tons"] / total["production_tons"], "0.000001"), "kwh_per_ton": None if not total["production_tons"] else _decimal(total["electricity_kwh"] / total["production_tons"], "0.000001")})
    totals = {name: sum((row[name] for row in enterprise7d), Decimal(0)) for name in (*measure_names, "production_tons")}
    zero_source = next(row for row in dwd_initial if row["enterprise_id"] == 3 and row["stat_date"] == as_of - dt.timedelta(days=1))
    manifest["expected"] = {"source_count": {"enterprise": len(enterprises), "utility": len(utility), "production": len(production)}, "dwd_initial_count": len(dwd_initial), "dwd_final_count": len(dwd_final_rows), "dwd_final_rows": dwd_final_rows, "source_anomaly_initial": len(utility) - len(dwd_initial), "source_anomaly_final": 0, "enterprise7d_rows": enterprise7d, "park7d_rows": 1, "park7d_totals": totals, "park7d_steam_per_ton": _decimal(totals["steam_tons"] / totals["production_tons"], "0.000001"), "park7d_kwh_per_ton": _decimal(totals["electricity_kwh"] / totals["production_tons"], "0.000001"), "park7d_row": {"park_id": 1, "as_of_date": as_of, "enterprise_count": len(enterprise7d), "valid_days": sum(row["valid_days"] for row in enterprise7d), **totals, "steam_per_ton": _decimal(totals["steam_tons"] / totals["production_tons"], "0.000001"), "kwh_per_ton": _decimal(totals["electricity_kwh"] / totals["production_tons"], "0.000001")}, "zero_production_row": {"enterprise_id": zero_source["enterprise_id"], "stat_date": zero_source["stat_date"], "production_tons": production_by_key[(3, zero_source["stat_date"])]["production_tons"], "steam_per_ton": None, "kwh_per_ton": None}}
    return _json_value(manifest)


def write_artifacts(scenario: dict[str, Any], output: Path) -> None:
    """Write offline artifacts without replacing an existing planned artifact."""
    output.mkdir(parents=True, exist_ok=True)
    content = {
        "manifest.json": scenario, "source.sql": scenario["seed_sql"], "models.json": scenario["models"],
        "transforms.sql": "\n\n".join(t["sqlText"] + ";" for t in scenario["transforms"]) + "\n",
        "metrics.json": scenario["metrics"], "expected.json": scenario["expected"],
        "dimensions.json": [model for model in scenario["models"] if model["layer"] == "DIM"],
        "quality.json": scenario["qualities"],
    }
    collisions = [output / name for name in content if (output / name).exists()]
    if collisions:
        raise FileExistsError("refusing to overwrite: " + ", ".join(str(p) for p in collisions))
    for name, value in content.items():
        path = output / name
        if isinstance(value, str):
            path.write_text(value, encoding="utf-8")
        else:
            path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the reproducible chemical-park DIM fixture")
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--as-of", required=True, type=dt.date.fromisoformat)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    write_artifacts(build_scenario(args.prefix, args.as_of), args.output)


if __name__ == "__main__":
    main()
