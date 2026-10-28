#!/usr/bin/env python3
"""Verify isolated partition configurations on the local Compose Doris stack."""
from __future__ import annotations

import json
import os
import re
import sys
import uuid

from smoke import Client, SmokeFailure, load_dotenv, post_form
from model_bucketing_smoke import doris, require


DYNAMIC = '''PROPERTIES(
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-1",
    "dynamic_partition.end" = "2",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.create_history_partition" = "true",
    "dynamic_partition.time_zone" = "Asia/Shanghai",
    "dynamic_partition.buckets" = "2"
)'''


def payload(table: str, kind: str, column: str) -> dict[str, str]:
    fields = {"tableName": table, "name": "分区验证 " + kind, "subjectArea": "测试", "layer": "DWD",
              "modelType": "DUPLICATE", "replicationNum": "1", "distributionType": "HASH",
              "distributionColumns": "id", "autoBuckets": "false", "buckets": "2",
              "partitionType": kind, "partitionColumn": column, "partitionUnit": "MONTH"}
    for index, (name, data_type) in enumerate([("dt", "DATE"), ("id", "BIGINT"), ("region", "VARCHAR(20)")]):
        fields.update({f"fields[{index}].name": name, f"fields[{index}].dataType": data_type,
                       f"fields[{index}].keyColumn": "true", f"fields[{index}].nullable": "false"})
    return fields


def compare(client: Client, detail: str, page: str, matches: bool = True) -> str:
    status, page = post_form(client, page, detail + "/compare", {})
    require(status, page, "逻辑模型与物理结构一致" if matches else "核对未通过")
    return page


def save(client: Client, fields: dict[str, str], detail: str = "") -> tuple[str, str]:
    status, form = client.request("GET", detail + "/edit" if detail else "/models/new")
    require(status, form, "model-form")
    status, page = post_form(client, form, detail or "/models", fields)
    require(status, page, "逻辑模型已保存")
    match = re.search(r'action="(/models/(\d+)/physicalize)"', page)
    if not match:
        raise SmokeFailure("saved model detail is unavailable")
    return "/models/" + match.group(2), page


def main() -> dict[str, object]:
    load_dotenv()
    client = Client()
    status, login = client.request("GET", "/login")
    status, page = post_form(client, login, "/login", {"username": "admin", "password": os.environ["PLATFORM_ADMIN_PASSWORD"]})
    require(status, page, "退出登录")
    prefix = "dwd_partition_smoke_" + uuid.uuid4().hex[:8]
    cases = [
        ("auto_range", "AUTO_RANGE", "dt", {"autoBuckets": "true"}, "AUTO PARTITION BY RANGE"),
        ("auto_list", "AUTO_LIST", "region", {}, "AUTO PARTITION BY LIST"),
        ("fixed", "MANUAL_RANGE", "dt", {"partitionRangeStyle": "FIXED",
            "partitions[0].name": "p_2026", "partitions[0].lowerBound": "2026-01-01", "partitions[0].upperBound": "2027-01-01"}, "PARTITION BY RANGE"),
        ("less_than", "MANUAL_RANGE", "id", {"partitionRangeStyle": "LESS_THAN",
            "partitions[0].name": "p_small", "partitions[0].upperBound": "100",
            "partitions[1].name": "p_other", "partitions[1].upperBound": "MAXVALUE"}, "PARTITION BY RANGE"),
        ("list", "MANUAL_LIST", "region", {"partitions[0].name": "p_regions", "partitions[0].valuesText": "east\nwest"}, "PARTITION BY LIST"),
        ("dynamic", "DYNAMIC", "dt", {"dynamicPartitionProperties": DYNAMIC}, "PARTITION BY RANGE"),
    ]
    evidence = []
    saved = {}
    for suffix, kind, column, extra, clause in cases:
        table = prefix + "_" + suffix
        fields = dict(payload(table, kind, column), **extra)
        detail, page = save(client, fields)
        require(200, page, clause)
        status, page = post_form(client, page, detail + "/physicalize", {"confirmed": "true"})
        require(status, page, "Doris 表已创建")
        page = compare(client, detail, page)
        ddl = doris(f"SHOW CREATE TABLE warehouse.`{table}`;")
        if clause not in ddl:
            raise SmokeFailure("physical partition type differs from expected: " + kind)
        date = "CURRENT_DATE()" if kind == "DYNAMIC" else "'2026-10-28'"
        doris(f"INSERT INTO warehouse.`{table}` VALUES ({date}, 1, 'east');")
        if doris(f"SELECT COUNT(*) FROM warehouse.`{table}`;") != "1":
            raise SmokeFailure("partition write failed: " + kind)
        status, page = client.request("GET", detail)
        page = compare(client, detail, page)
        status, edit = client.request("GET", detail + "/edit")
        require(status, edit, f'value="{kind}" selected="selected"')
        if kind.startswith("MANUAL"):
            require(status, edit, fields["partitions[0].name"])
        if kind == "DYNAMIC":
            require(status, edit, "dynamic_partition.create_history_partition")
        saved[suffix] = (detail, fields)
        evidence.append({"modelId": int(detail.rsplit("/", 1)[1]), "table": table, "type": kind})
    # Drift only the isolated logical definitions, then restore them.
    for suffix, patch in [("fixed", {"partitions[0].upperBound": "2028-01-01"}),
                          ("list", {"partitions[0].valuesText": "east\nwest\nnorth"}),
                          ("dynamic", {"dynamicPartitionProperties": DYNAMIC.replace('"2",\n    "dynamic_partition.prefix"', '"3",\n    "dynamic_partition.prefix"')})]:
        detail, original = saved[suffix]
        detail, page = save(client, dict(original, **patch), detail)
        compare(client, detail, page, False)
        detail, page = save(client, original, detail)
        compare(client, detail, page)
    return {"status": "success", "checks": ["six-partition-configurations", "real-partition-writes",
            "compare-before-and-after-ingestion", "edit-refill", "manual-boundaries-and-list-values-and-dynamic-policy-drift"], "evidence": evidence}


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({"status": "failed", "reason": str(error) if isinstance(error, SmokeFailure) else type(error).__name__}))
        sys.exit(1)
