#!/usr/bin/env python3
"""Exercise modeling pages against the deployed platform and real Doris; keep the test table as evidence."""
from __future__ import annotations

import json
import os
import re
import sys
import uuid
from smoke import Client, SmokeFailure, load_dotenv, parse, post_form, require_success


def confirmed(status: int, page: str, expected: str) -> None:
    try:
        require_success(status, page, expected)
    except SmokeFailure as error:
        raise SmokeFailure("operation not confirmed: " + expected) from error


def main() -> dict[str, object]:
    load_dotenv()
    password = os.environ.get("PLATFORM_ADMIN_PASSWORD")
    if not password:
        raise SmokeFailure("admin password is required")
    client = Client()
    status, login = client.request("GET", "/login")
    status, dashboard = post_form(client, login, "/login", {"username": "admin", "password": password})
    if status != 200 or "退出登录" not in dashboard:
        raise SmokeFailure("login failed")
    table = "ods_model_smoke_" + uuid.uuid4().hex[:10]
    status, page = client.request("GET", "/models/new")
    if status != 200:
        raise SmokeFailure("model form unavailable")
    fields = {"tableName": table, "name": "建模冒烟验证", "subjectArea": "测试域", "layer": "ODS", "buckets": "1", "description": "真实 Doris 建表与结构核对"}
    definitions = [
        ("id", "BIGINT", True, False, "业务 '主键' ID"),
        ("label", "VARCHAR(128)", False, True, "名称"),
        ("amount", "DECIMAL(18,2)", False, False, "金额"),
        ("updated_at", "DATETIME(6)", False, False, "更新时间"),
        ("business_date", "DATE", False, True, "日期"),
        ("created_at", "DATETIME(0)", False, True, "秒精度"),
        ("enabled", "BOOLEAN", False, True, "布尔值"),
        ("code", "CHAR(8)", False, True, "编码"),
        ("ratio", "DOUBLE", False, True, "比例"),
        ("notes", "STRING", False, True, "备注"),
    ]
    for index, (name, data_type, key, nullable, comment) in enumerate(definitions):
        for property_name, value in {"name": name, "dataType": data_type, "keyColumn": str(key).lower(),
                                     "nullable": str(nullable).lower(), "comment": comment, "standard": "STD-001" if index == 2 else ""}.items():
            fields[f"fields[{index}].{property_name}"] = value
    status, page = post_form(client, page, "/models", fields)
    confirmed(status, page, "逻辑模型已保存")
    actions = [str(item["action"]) for item in parse(page).forms]
    model_id = next((re.fullmatch(r"/models/(\d+)/physicalize", item).group(1)
                     for item in actions if re.fullmatch(r"/models/(\d+)/physicalize", item)), None)
    if not model_id:
        raise SmokeFailure("model detail unavailable")
    detail_path = f"/models/{model_id}"
    physicalize = detail_path + "/physicalize"
    original_form = next(item for item in parse(page).forms if item["action"] == physicalize)
    old_preview = dict(original_form["inputs"])
    status, page = post_form(client, page, physicalize, {"confirmed": "true"})
    confirmed(status, page, "Doris 表已创建")
    status, page = post_form(client, page, detail_path + "/compare", {})
    confirmed(status, page, "逻辑模型与物理结构一致")
    status, page = post_form(client, page, physicalize, {"confirmed": "true"})
    if status != 200 or "alert-danger" not in page or "FAILED" not in page:
        raise SmokeFailure("duplicate create must fail and retain execution history")
    status, edit = client.request("GET", detail_path + "/edit")
    fields["version"] = "1"
    fields["fields[1].comment"] = "逻辑模型已更新"
    status, page = post_form(client, edit, detail_path, fields)
    confirmed(status, page, "逻辑模型已保存")
    # A stale form has an old version, even if its CSRF token is current.
    status, stale = client.request("POST", physicalize, {**old_preview, "confirmed": "true"})
    if status != 200 or "模型已被修改" not in stale:
        raise SmokeFailure("stale preview was not rejected")
    status, page = client.request("GET", detail_path)
    status, page = post_form(client, page, detail_path + "/compare", {})
    if status != 200 or "结构比对结果" not in page or "差异" not in page or "DIFF" not in page:
        raise SmokeFailure("logical comment drift was not detected")
    return {"status": "success", "modelId": int(model_id), "table": "warehouse." + table,
            "checks": ["save", "preview", "real-create", "type-and-comment-roundtrip", "compare-match", "duplicate-failure-record", "edit", "stale-preview-rejected", "compare-drift"]}


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False))
    except Exception as error:
        # Driver/server payloads can contain credentials; print only the failing stage, never a response body.
        print(json.dumps({"status": "failed", "reason": str(error) if isinstance(error, SmokeFailure) else type(error).__name__}))
        sys.exit(1)
