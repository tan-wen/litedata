#!/usr/bin/env python3
"""Real-environment smoke test for the quality-gated DATA_READY flow.

This deliberately writes three synthetic rows to the *demo source* MySQL
container.  It never accesses LiteData's platform database directly: all
platform resources, configuration changes and run requests use the public
HTML/JSON endpoints with normal CSRF handling.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import uuid

from smoke import (ensure_subject_area, Client, SmokeFailure, create_workflow, create_workflow_sql,
                   id_from_row, load_dotenv, parse, post_form, request_json,
                   require_success, workflow_action)


TIMEOUT = 360


def checked(status: int, page: str, expected: str) -> None:
    try:
        require_success(status, page, expected)
    except SmokeFailure as error:
        raise SmokeFailure("stage not confirmed: " + expected) from error


def model(client: Client, table: str, layer: str, definitions: list[tuple[str, str, bool]]) -> str:
    _, page = client.request("GET", "/models/new")
    fields = {
        "tableName": table, "tableNameSuffix": table.removeprefix(layer.lower() + "_"), "name": table, "subjectArea": "质量门禁冒烟", "layer": layer,
        "buckets": "1", "modelType": "UNIQUE", "replicationNum": "1", "distributionType": "HASH",
        "autoBuckets": "false", "distributionColumns": ",".join(name for name, _, key in definitions if key),
        "partitionType": "NONE",
    }
    for index, (name, data_type, key) in enumerate(definitions):
        for prop, value in {
            "name": name, "dataType": data_type, "keyColumn": str(key).lower(), "nullable": "false",
            "comment": "", "standard": "",
        }.items():
            fields[f"fields[{index}].{prop}"] = value
    ensure_subject_area(client, fields["subjectArea"], page)
    status, page = post_form(client, page, "/models", fields)
    checked(status, page, "逻辑模型已保存")
    match = next((re.fullmatch(r"/models/(\d+)/physicalize", str(form["action"])) for form in parse(page).forms
                  if re.fullmatch(r"/models/\d+/physicalize", str(form["action"]))), None)
    if not match:
        raise SmokeFailure("physicalize form was absent")
    status, page = post_form(client, page, f"/models/{match.group(1)}/physicalize", {"confirmed": "true"})
    checked(status, page, "Doris 表已创建")
    return match.group(1)


def quality_fields(name: str, table: str, version: str, severity: str, minimum: str, maximum: str) -> dict[str, str]:
    return {
        "name": name, "sourceId": "", "tableName": table, "owner": "readiness-smoke",
        "description": "Synthetic quality gate smoke fixture", "cronExpression": "", "timeColumn": "updated_at",
        "windowHours": "24", "timeoutSeconds": "60", "version": version,
        "rules[0].name": "synthetic-row-count", "rules[0].type": "ROW_COUNT", "rules[0].columns": "",
        "rules[0].parameter1": minimum, "rules[0].parameter2": maximum, "rules[0].referenceTable": "",
        "rules[0].referenceColumn": "", "rules[0].toleranceCount": "0", "rules[0].tolerancePercent": "0",
        "rules[0].severity": severity,
    }


def create_quality(client: Client, name: str, table: str) -> str:
    _, page = client.request("GET", "/quality/new")
    status, page = post_form(client, page, "/quality", quality_fields(name, table, "0", "CRITICAL", "999999", "999999"))
    checked(status, page, "质量任务已保存")
    return id_from_row(page, name, "/edit")


def update_quality(client: Client, quality_id: str, name: str, table: str, severity: str, minimum: str, maximum: str) -> None:
    _, page = client.request("GET", f"/quality/{quality_id}/edit")
    form = next((item for item in parse(page).forms if str(item["action"]) == f"/quality/{quality_id}"), None)
    if form is None:
        raise SmokeFailure("quality edit form was absent")
    version = str(form["inputs"].get("version", ""))
    if not version.isdigit():
        raise SmokeFailure("quality task version was absent")
    status, page = post_form(client, page, f"/quality/{quality_id}", quality_fields(name, table, version, severity, minimum, maximum))
    checked(status, page, "质量任务已保存")


def workflow_metadata(client: Client, workflow_id: str, name: str, version: int, pipeline_id: str) -> None:
    _, page = client.request("GET", f"/workflows/{workflow_id}/edit")
    status, page = post_form(client, page, f"/workflows/{workflow_id}/metadata", {
        "name": name, "description": "Synthetic readiness smoke fixture", "version": str(version),
        "triggerMode": "EVENT", "triggerPipelineId": pipeline_id,
    })
    checked(status, page, "加工流程已保存")


def save_sql_only_graph(client: Client, workflow_id: str, page: str, view: dict) -> dict:
    nodes = [node for node in view.get("nodes", []) if node.get("kind") == "SQL"]
    if len(nodes) != 1:
        raise SmokeFailure("workflow must contain exactly one SQL node")
    return request_json(client, "POST", f"/workflows/{workflow_id}/graph", {
        "version": view["version"], "graphHash": view["graphHash"], "cronExpression": "0 0 * * * ?",
        "nodes": nodes, "edges": [], "positions": {},
    }, page)


def synthetic_source_row(batch: int) -> None:
    """Advance demo orders' watermark without exposing a password or row contents."""
    password = os.environ.get("MYSQL_ROOT_PASSWORD")
    if not password:
        raise SmokeFailure("MYSQL_ROOT_PASSWORD is required to create synthetic source batches")
    row_id = int(time.time_ns() // 1_000_000) + batch
    sql = ("INSERT INTO orders(id,customer_name,amount,updated_at) VALUES "
           f"({row_id},'Readiness Smoke',{batch}.00,NOW(6))")
    env = os.environ.copy()
    env["MYSQL_PWD"] = password
    try:
        subprocess.run(["docker", "compose", "exec", "-T", "-e", "MYSQL_PWD", "demo-mysql", "mysql", "-uroot", "demo_source", "-e", sql],
                       cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))), env=env,
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       check=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        raise SmokeFailure("cannot add the synthetic demo-source batch") from error


def latest_instance(client: Client, pipeline_id: str, previous: set[int]) -> int | None:
    status, page = client.request("GET", f"/scheduling/pipeline/{pipeline_id}")
    if status != 200:
        raise SmokeFailure("pipeline scheduler history is unavailable")
    found = {int(value) for value in re.findall(rf"/scheduling/pipeline/{re.escape(pipeline_id)}/instances/(\d+)", page)}
    fresh = found - previous
    return max(fresh) if fresh else None


def callback_status(client: Client, pipeline_id: str, instance_id: int) -> dict | None:
    status, payload = client.request("GET", f"/scheduling/pipeline/{pipeline_id}/instances/{instance_id}/status")
    if status != 200:
        return None
    try:
        data = json.loads(payload)
    except json.JSONDecodeError as error:
        raise SmokeFailure("pipeline status endpoint returned invalid JSON") from error
    items = data.get("callbackPage", {}).get("items", [])
    return items[0] if items else None


def trigger_and_wait(client: Client, pipeline_id: str, expected_quality: str) -> dict:
    status, page = client.request("GET", f"/scheduling/pipeline/{pipeline_id}")
    if status != 200:
        raise SmokeFailure("pipeline scheduler page is unavailable")
    before = {int(value) for value in re.findall(rf"/scheduling/pipeline/{re.escape(pipeline_id)}/instances/(\d+)", page)}
    _, pipeline_page = client.request("GET", "/pipelines")
    status, page = post_form(client, pipeline_page, f"/pipelines/{pipeline_id}/run", {})
    checked(status, page, "已接受执行请求")
    deadline = time.monotonic() + TIMEOUT
    instance_id = None
    while time.monotonic() < deadline:
        instance_id = instance_id or latest_instance(client, pipeline_id, before)
        if instance_id is not None:
            callback = callback_status(client, pipeline_id, instance_id)
            if callback and callback.get("status") == "SUCCESS" and callback.get("qualityStatus") == expected_quality:
                return {"instanceId": instance_id, "runId": callback.get("id"), "qualityRunId": callback.get("qualityRunId")}
            if callback and callback.get("status") == "FAILED":
                raise SmokeFailure("synthetic pipeline callback failed")
        time.sleep(3)
    raise SmokeFailure("pipeline callback did not reach expected quality status")


def transform_successes(client: Client, task_id: str, task_name: str) -> int:
    status, page = client.request("GET", f"/transforms/{task_id}")
    if status != 200:
        raise SmokeFailure("transform history is unavailable")
    return sum(1 for row in parse(page).rows
               if len(row["cells"]) >= 4 and str(row["cells"][1]).split(" / ")[0] == task_name and row["cells"][3] == "SUCCESS")


def wait_transform_successes(client: Client, task_id: str, task_name: str, expected: int) -> None:
    deadline = time.monotonic() + TIMEOUT
    while time.monotonic() < deadline:
        if transform_successes(client, task_id, task_name) >= expected:
            return
        time.sleep(3)
    raise SmokeFailure("DATA_READY did not start the SQL-only processing workflow")


def ensure_no_transform(client: Client, task_id: str, task_name: str, expected: int) -> None:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if transform_successes(client, task_id, task_name) != expected:
            raise SmokeFailure("critical quality failure unexpectedly started processing")
        time.sleep(2)


def main() -> dict:
    load_dotenv()
    client = Client()
    _, page = client.request("GET", "/login")
    status, page = post_form(client, page, "/login", {"username": "admin", "password": os.environ["PLATFORM_ADMIN_PASSWORD"]})
    if status != 200 or "退出登录" not in page:
        raise SmokeFailure("login failed")

    suffix = uuid.uuid4().hex[:8]
    source_name, pipeline_name = "ready-source-" + suffix, "ready-pipeline-" + suffix
    ods, dwd = "ods_ready_" + suffix, "dwd_ready_" + suffix
    quality_name, workflow_name, task_name = "ready-quality-" + suffix, "ready-workflow-" + suffix, "ready-task-" + suffix
    columns = [("id", "BIGINT", True), ("customer_name", "VARCHAR(128)", False),
               ("amount", "DECIMAL(18,2)", False), ("updated_at", "DATETIME(6)", False)]
    ods_model = model(client, ods, "ODS", columns)
    dwd_model = model(client, dwd, "DWD", columns)
    print(json.dumps({"stage": "models-created", "odsModelId": int(ods_model), "dwdModelId": int(dwd_model)}), flush=True)

    _, page = client.request("GET", "/sources/new")
    status, page = post_form(client, page, "/sources", {"name": source_name,
        "jdbcUrl": "jdbc:mysql://demo-mysql:3306/demo_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "username": "demo_reader", "password": "demo_reader_password"})
    checked(status, page, "数据源已保存")
    source_id = id_from_row(page, source_name, "/test")
    quality_id = create_quality(client, quality_name, ods)
    _, page = client.request("GET", "/pipelines/new")
    status, page = post_form(client, page, "/pipelines", {"name": pipeline_name, "sourceId": source_id,
        "sourceTable": "orders", "watermarkColumn": "updated_at", "targetMode": "EXISTING", "targetTable": ods,
        "cronExpression": "0 0 * * * ?", "qualityTaskId": quality_id})
    checked(status, page, "ODS 同步管道已保存")
    pipeline_id = id_from_row(page, pipeline_name, "/run")

    workflow_id, workflow_page = create_workflow(client, workflow_name)
    task_id, graph = create_workflow_sql(client, workflow_id, workflow_page, task_name, dwd_model,
        f"INSERT INTO {dwd} SELECT id,customer_name,amount,updated_at FROM {ods}")
    workflow_metadata(client, workflow_id, workflow_name, graph["version"], pipeline_id)
    graph = request_json(client, "GET", f"/workflows/{workflow_id}/graph")
    save_sql_only_graph(client, workflow_id, workflow_page, graph)
    status, page = workflow_action(client, workflow_id, "schedule")
    checked(status, page, "加工流程事件订阅已启用")
    print(json.dumps({"stage": "resources-ready", "pipelineId": int(pipeline_id), "qualityTaskId": int(quality_id), "workflowId": int(workflow_id)}), flush=True)

    synthetic_source_row(1)
    failed = trigger_and_wait(client, pipeline_id, "FAILED")
    ensure_no_transform(client, task_id, task_name, 0)
    print(json.dumps({"stage": "critical-blocked", "pipelineRunId": failed["runId"], "qualityRunId": failed["qualityRunId"], "qualityStatus": "FAILED"}), flush=True)

    update_quality(client, quality_id, quality_name, ods, "WARNING", "999999", "999999")
    synthetic_source_row(2)
    warning = trigger_and_wait(client, pipeline_id, "WARNING")
    wait_transform_successes(client, task_id, task_name, 1)
    print(json.dumps({"stage": "warning-released", "pipelineRunId": warning["runId"], "qualityRunId": warning["qualityRunId"], "qualityStatus": "WARNING"}), flush=True)

    update_quality(client, quality_id, quality_name, ods, "CRITICAL", "1", "999999")
    synthetic_source_row(3)
    passed = trigger_and_wait(client, pipeline_id, "PASSED")
    wait_transform_successes(client, task_id, task_name, 2)
    print(json.dumps({"stage": "passed-released", "pipelineRunId": passed["runId"], "qualityRunId": passed["qualityRunId"], "qualityStatus": "PASSED"}), flush=True)
    return {"status": "success", "checks": ["critical-blocks-data-ready", "warning-releases-data-ready", "passed-releases-data-ready"],
            "resources": {"pipelineId": int(pipeline_id), "qualityTaskId": int(quality_id), "workflowId": int(workflow_id), "sqlTaskId": int(task_id)}}


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False), flush=True)
    except Exception as error:
        print(json.dumps({"status": "failed", "reason": str(error) if isinstance(error, SmokeFailure) else type(error).__name__}), flush=True)
        sys.exit(1)
