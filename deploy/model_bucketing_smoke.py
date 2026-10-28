#!/usr/bin/env python3
"""Verify configurable Doris bucketing against a deployed LiteData Compose stack.

The test intentionally retains its dws_bucket_smoke_* tables as evidence. It never
prints environment values, passwords, or generated form fields.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import uuid

from smoke import Client, SmokeFailure, load_dotenv, post_form


def require(status: int, page: str, text: str) -> None:
    if status != 200 or text not in page:
        raise SmokeFailure("expected platform confirmation is absent: " + text)


def doris(sql: str) -> str:
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "doris", "mysql", "--protocol=tcp",
         "--host=127.0.0.1", "--port=9030", "--user=root", "-N", "-B"],
        input=sql, capture_output=True, text=True, timeout=30,
    )
    if result.returncode:
        raise SmokeFailure("isolated Doris verification query failed")
    return result.stdout.strip()


def model_fields(model_type: str) -> list[tuple[str, str, bool, bool, str]]:
    base = [("id", "BIGINT", True, False, ""), ("tenant_id", "BIGINT", True, False, "")]
    if model_type == "AGGREGATE":
        return base + [("amount", "DECIMAL(18,2)", False, False, "SUM"),
                       ("label", "VARCHAR(20)", False, True, "REPLACE")]
    return base + [("label", "VARCHAR(20)", False, True, ""),
                   ("amount", "DECIMAL(18,2)", False, False, "")]


def create_model(client: Client, table: str, label: str, model_type: str, distribution_type: str,
                 distribution_columns: str, auto_buckets: bool, buckets: int) -> tuple[str, str, dict[str, str]]:
    fields: dict[str, str] = {
        "tableName": table, "name": "分桶验证 " + label, "subjectArea": "测试域", "layer": "DWS",
        "modelType": model_type, "replicationNum": "1", "distributionType": distribution_type,
        "distributionColumns": distribution_columns, "autoBuckets": str(auto_buckets).lower(),
        "buckets": str(buckets), "partitionType": "NONE", "partitionColumn": "", "partitionUnit": "DAY",
    }
    for index, (name, data_type, key, nullable, aggregation) in enumerate(model_fields(model_type)):
        fields.update({
            f"fields[{index}].name": name, f"fields[{index}].dataType": data_type,
            f"fields[{index}].keyColumn": str(key).lower(), f"fields[{index}].nullable": str(nullable).lower(),
            f"fields[{index}].aggregation": aggregation,
        })
    status, form = client.request("GET", "/models/new")
    if status != 200:
        raise SmokeFailure("model create page is unavailable")
    status, detail_page = post_form(client, form, "/models", fields)
    require(status, detail_page, "逻辑模型已保存")
    distribution = (f"DISTRIBUTED BY {distribution_type}"
                    + (f"(`{distribution_columns}`)" if distribution_type == "HASH" else "")
                    + (" BUCKETS AUTO" if auto_buckets else f" BUCKETS {buckets}"))
    require(status, detail_page, distribution)
    match = re.search(r'action="(/models/(\d+)/physicalize)"', detail_page)
    if not match:
        raise SmokeFailure("model detail did not expose physicalize form")
    detail = "/models/" + match.group(2)
    status, physical_page = post_form(client, detail_page, match.group(1), {"confirmed": "true"})
    require(status, physical_page, "Doris 表已创建")
    status, comparison = post_form(client, physical_page, detail + "/compare", {})
    require(status, comparison, "逻辑模型与物理结构一致")
    return detail, comparison, fields


def expect_ddl(table: str, distribution: str, auto_buckets: bool, buckets: int) -> None:
    ddl = doris(f"SHOW CREATE TABLE warehouse.`{table}`;")
    if distribution not in ddl:
        raise SmokeFailure("Doris DDL has a different bucket distribution")
    expected_buckets = "BUCKETS AUTO" if auto_buckets else f"BUCKETS {buckets}"
    if expected_buckets not in ddl:
        raise SmokeFailure("Doris DDL has a different bucket count")


def require_edit_refill(client: Client, detail: str, distribution_type: str, distribution_columns: str,
                        auto_buckets: bool, buckets: int) -> str:
    status, edit = client.request("GET", detail + "/edit")
    if status != 200:
        raise SmokeFailure("model edit page is unavailable")
    expected = [f'value="{distribution_type}" selected="selected"', f'value="{buckets}"']
    if distribution_type == "HASH":
        expected.append(f'value="{distribution_columns}"')
    if auto_buckets:
        expected.append('name="autoBuckets" value="true" checked="checked"')
    for value in expected:
        if value not in edit:
            raise SmokeFailure("model edit form did not refill a bucket setting")
    return edit


def main() -> dict[str, object]:
    load_dotenv()
    password = os.environ.get("PLATFORM_ADMIN_PASSWORD")
    if not password:
        raise SmokeFailure("PLATFORM_ADMIN_PASSWORD is required")
    client = Client()
    status, login = client.request("GET", "/login")
    if status != 200:
        raise SmokeFailure("login page is unavailable")
    status, page = post_form(client, login, "/login", {"username": "admin", "password": password})
    require(status, page, "退出登录")

    prefix = "dws_bucket_smoke_" + uuid.uuid4().hex[:10]
    cases = [
        ("unique_hash_fixed", "UNIQUE", "HASH", "id", False, 8, "DISTRIBUTED BY HASH(`id`) BUCKETS 8"),
        ("duplicate_hash_auto", "DUPLICATE", "HASH", "label", True, 1, "DISTRIBUTED BY HASH(`label`) BUCKETS AUTO"),
        ("duplicate_random_fixed", "DUPLICATE", "RANDOM", "", False, 4, "DISTRIBUTED BY RANDOM BUCKETS 4"),
        ("duplicate_random_auto", "DUPLICATE", "RANDOM", "", True, 1, "DISTRIBUTED BY RANDOM BUCKETS AUTO"),
        ("aggregate_hash_fixed", "AGGREGATE", "HASH", "tenant_id", False, 4, "DISTRIBUTED BY HASH(`tenant_id`) BUCKETS 4"),
    ]
    evidence: list[dict[str, object]] = []
    first_detail = ""
    first_table = ""
    first_fields: dict[str, str] = {}
    duplicate_detail = ""
    duplicate_fields: dict[str, str] = {}
    for suffix, model_type, distribution_type, distribution_columns, auto_buckets, buckets, distribution in cases:
        table = prefix + "_" + suffix
        detail, _, fields = create_model(client, table, suffix, model_type, distribution_type, distribution_columns, auto_buckets, buckets)
        expect_ddl(table, distribution, auto_buckets, buckets)
        edit = require_edit_refill(client, detail, distribution_type, distribution_columns, auto_buckets, buckets)
        if not first_detail:
            first_detail, first_table, first_fields = detail, table, fields
        if suffix == "duplicate_hash_auto":
            duplicate_detail, duplicate_fields = detail, fields
        evidence.append({"table": table, "model": model_type, "distribution": distribution_type,
                         "autoBuckets": auto_buckets, "buckets": buckets})

    # Change one existing logical model only; no ALTER is issued to its Doris table.
    status, first_edit = client.request("GET", first_detail + "/edit")
    if status != 200:
        raise SmokeFailure("model edit page is unavailable before drift check")
    changed_fields = dict(first_fields, distributionType="HASH", distributionColumns="tenant_id", autoBuckets="false", buckets="4")
    status, changed = post_form(client, first_edit, first_detail, changed_fields)
    require(status, changed, "逻辑模型已保存")
    status, drift = post_form(client, changed, first_detail + "/compare", {})
    require(status, drift, "核对未通过")
    status, original_edit = client.request("GET", first_detail + "/edit")
    status, restored = post_form(client, original_edit, first_detail, first_fields)
    require(status, restored, "逻辑模型已保存")
    status, restored_compare = post_form(client, restored, first_detail + "/compare", {})
    require(status, restored_compare, "逻辑模型与物理结构一致")
    status, duplicate_edit = client.request("GET", duplicate_detail + "/edit")
    if status != 200:
        raise SmokeFailure("duplicate model edit page is unavailable before distribution drift check")
    random_fields = dict(duplicate_fields, distributionType="RANDOM", distributionColumns="", autoBuckets="false", buckets="4")
    status, random_changed = post_form(client, duplicate_edit, duplicate_detail, random_fields)
    require(status, random_changed, "逻辑模型已保存")
    status, random_drift = post_form(client, random_changed, duplicate_detail + "/compare", {})
    require(status, random_drift, "核对未通过")
    status, duplicate_restore_edit = client.request("GET", duplicate_detail + "/edit")
    status, duplicate_restored = post_form(client, duplicate_restore_edit, duplicate_detail, duplicate_fields)
    require(status, duplicate_restored, "逻辑模型已保存")
    status, duplicate_restored_compare = post_form(client, duplicate_restored, duplicate_detail + "/compare", {})
    require(status, duplicate_restored_compare, "逻辑模型与物理结构一致")
    return {"status": "success", "checks": ["five-configurations", "save-preview-physicalize-show-create-compare-edit-refill",
            "hash-subset-key", "hash-non-key", "random-fixed-and-auto", "bucket-key-count-and-type-drift-detection"], "evidence": evidence,
            "driftTable": first_table}


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False, separators=(",", ":")))
    except Exception as error:
        print(json.dumps({"status": "failed", "reason": str(error) if isinstance(error, SmokeFailure) else type(error).__name__}, ensure_ascii=False, separators=(",", ":")))
        sys.exit(1)
