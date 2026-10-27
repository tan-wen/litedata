#!/usr/bin/env python3
"""Verify configurable modeling with the local Compose Doris; retain isolated test tables as evidence."""
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
        raise SmokeFailure("operation not confirmed: " + text)


def doris(sql: str) -> str:
    result = subprocess.run(["docker", "compose", "exec", "-T", "doris", "mysql", "--protocol=tcp",
                             "--host=127.0.0.1", "--port=9030", "--user=root", "-N", "-B"],
                            input=sql, capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise SmokeFailure("isolated Doris test query failed")
    return result.stdout.strip()


def main() -> dict[str, object]:
    load_dotenv()
    client = Client()
    status, page = client.request("GET", "/login")
    status, page = post_form(client, page, "/login", {"username": "admin", "password": os.environ["PLATFORM_ADMIN_PASSWORD"]})
    require(status, page, "退出登录")
    evidence = []
    for kind, unit in [("UNIQUE", "DAY"), ("DUPLICATE", "YEAR"), ("AGGREGATE", "MONTH")]:
        table = "dws_config_smoke_" + uuid.uuid4().hex[:10]
        fields = {"tableName": table, "name": "物理配置验证 " + kind, "subjectArea": "测试域", "layer": "DWS",
                  "modelType": kind, "replicationNum": "1", "autoBuckets": "true", "buckets": "2", "partitionType": "AUTO_RANGE",
                  "partitionColumn": "dt", "partitionUnit": unit}
        definitions = [("dt", "DATE", True, False, ""), ("id", "BIGINT", True, False, ""),
                       ("amount", "DECIMAL(18,2)", False, False, "SUM"),
                       ("label", "VARCHAR(20)", False, True, "REPLACE")]
        values = ["'2026-10-27'", "1", "10", "'test'"]
        if kind == "AGGREGATE":
            definitions += [("low", "VARCHAR(20)", False, True, "MIN"), ("high", "STRING", False, True, "MAX"),
                            ("latest", "DATETIME(6)", False, True, "REPLACE_IF_NOT_NULL")]
            values += ["'a'", "'z'", "'2026-10-27 00:00:00'"]
        for index, (name, data_type, key, nullable, function) in enumerate(definitions):
            for prop, value in {"name": name, "dataType": data_type, "keyColumn": str(key).lower(),
                                "nullable": str(nullable).lower(), "aggregation": function if kind == "AGGREGATE" else ""}.items():
                fields[f"fields[{index}].{prop}"] = value
        status, page = client.request("GET", "/models/new")
        status, page = post_form(client, page, "/models", fields)
        require(status, page, "逻辑模型已保存")
        require(status, page, "BUCKETS AUTO")
        match = re.search(r'action="(/models/(\d+)/physicalize)"', page)
        if not match:
            raise SmokeFailure("model detail unavailable")
        detail = "/models/" + match.group(2)
        status, page = post_form(client, page, match.group(1), {"confirmed": "true"})
        require(status, page, "Doris 表已创建")
        status, page = post_form(client, page, detail + "/compare", {})
        require(status, page, "逻辑模型与物理结构一致")
        if "BUCKETS AUTO" not in doris(f"SHOW CREATE TABLE warehouse.`{table}`;"):
            raise SmokeFailure("automatic bucket policy did not round trip")
        insert = f"INSERT INTO warehouse.`{table}` VALUES ({', '.join(values)});"
        doris(insert + "\n" + insert)
        data = doris(f"SELECT COUNT(*), SUM(amount) FROM warehouse.`{table}`;")
        expected = "1\t10.00" if kind == "UNIQUE" else "2\t20.00" if kind == "DUPLICATE" else "1\t20.00"
        if data != expected:
            raise SmokeFailure("write semantics mismatch: " + kind)
        status, page = client.request("GET", detail)
        status, page = post_form(client, page, detail + "/compare", {})
        require(status, page, "逻辑模型与物理结构一致")
        status, page = client.request("GET", detail + "/edit")
        require(status, page, 'value="' + kind + '" selected="selected"')
        fields["version"] = "1"
        fields["replicationNum"] = "2"
        status, page = post_form(client, page, detail, fields)
        require(status, page, "逻辑模型已保存")
        status, page = post_form(client, page, detail + "/compare", {})
        require(status, page, "核对未通过")
        fields["version"] = "2"
        fields["replicationNum"] = "1"
        status, page = client.request("GET", detail + "/edit")
        status, page = post_form(client, page, detail, fields)
        status, page = post_form(client, page, detail + "/compare", {})
        require(status, page, "逻辑模型与物理结构一致")
        evidence.append({"modelId": int(match.group(2)), "table": table, "type": kind, "unit": unit})
    return {"status": "success", "checks": ["three-models", "five-aggregate-functions", "date-auto-partitions", "auto-buckets",
            "real-write-semantics", "compare-before-and-after-ingestion", "edit-persistence", "replica-drift"], "evidence": evidence}


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({"status": "failed", "reason": str(error) if isinstance(error, SmokeFailure) else type(error).__name__}))
        sys.exit(1)
