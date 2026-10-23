#!/usr/bin/env python3
"""Real DataX -> ODS -> DWD -> DWS -> ADS DAG, timer, and failure-gating smoke test."""
import json
import os
import re
import sys
import time
import uuid
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from smoke import Client, SmokeFailure, load_dotenv, parse, post_form, require_success, id_from_row, generated_key


def checked(status, page, text):
    try:
        require_success(status, page, text)
    except SmokeFailure as error:
        raise SmokeFailure("stage not confirmed: " + text) from error


def resource_id(page, prefix, suffix):
    for form in parse(page).forms:
        match = re.fullmatch(prefix + r"/(\d+)/" + suffix, str(form["action"]))
        if match:
            return match.group(1)
    raise SmokeFailure("created resource detail was absent")


def model(client, table, layer, definitions):
    _, page = client.request("GET", "/models/new")
    fields = {"tableName": table, "name": table, "subjectArea": "加工验证域", "layer": layer, "buckets": "1"}
    for i, (name, data_type, key) in enumerate(definitions):
        for prop, value in {"name": name, "dataType": data_type, "keyColumn": str(key).lower(), "nullable": "false", "comment": "", "standard": ""}.items():
            fields[f"fields[{i}].{prop}"] = value
    status, page = post_form(client, page, "/models", fields)
    checked(status, page, "逻辑模型已保存")
    identifier = resource_id(page, "/models", "physicalize")
    status, page = post_form(client, page, f"/models/{identifier}/physicalize", {"confirmed": "true"})
    checked(status, page, "Doris 表已创建")
    return identifier


def task(client, name, model_id, sql, upstream=None, pipeline=None):
    _, page = client.request("GET", "/transforms/new")
    fields = {"name": name, "modelId": model_id, "sqlText": sql, "cronExpression": "0 */5 * * * ?"}
    if upstream:
        fields["upstreamTaskIds"] = upstream
    if pipeline:
        fields["upstreamPipelineIds"] = pipeline
    status, page = post_form(client, page, "/transforms", fields)
    checked(status, page, "SQL 加工任务已保存")
    return resource_id(page, "/transforms", "run")


def runs(client, root):
    _, page = client.request("GET", f"/transforms/{root}")
    result = []
    for row in parse(page).rows:
        cells = row["cells"]
        if len(cells) == 6 and str(cells[0]).isdigit() and cells[3] in ("RUNNING", "SUCCESS", "FAILED"):
            result.append({"id": int(cells[0]), "task": cells[1].split(" / ")[0], "status": cells[3]})
    return page, result


def wait_runs(client, root, names, count, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        _, records = runs(client, root)
        if any(row["status"] == "FAILED" for row in records):
            raise SmokeFailure("SQL node failed during success scenario")
        if all(sum(row["task"] == name and row["status"] == "SUCCESS" for row in records) >= count for name in names):
            return records
        time.sleep(5)
    raise SmokeFailure("SQL DAG did not complete within timeout")


def engine_instances(root):
    project = os.environ["DOLPHINSCHEDULER_PROJECT_CODE"]
    port = os.environ.get("DOLPHINSCHEDULER_PORT", "12345")
    base = os.environ.get("SMOKE_DS_URL", f"http://localhost:{port}/dolphinscheduler")
    query = urlencode({"pageNo": 1, "pageSize": 20, "searchVal": f"platform-sql-{root}-"})
    request = Request(f"{base}/projects/{project}/workflow-instances?{query}", headers={"token": os.environ["DOLPHINSCHEDULER_TOKEN"]})
    with urlopen(request, timeout=20) as response:
        data = json.load(response)
    if data.get("code") != 0:
        raise SmokeFailure("cannot read DS workflow instance status")
    return data.get("data", {}).get("totalList", [])


def main():
    load_dotenv()
    client = Client()
    _, page = client.request("GET", "/login")
    status, page = post_form(client, page, "/login", {"username": "admin", "password": os.environ["PLATFORM_ADMIN_PASSWORD"]})
    if status != 200 or "退出登录" not in page:
        raise SmokeFailure("login failed")
    suffix = uuid.uuid4().hex[:8]
    dwd, dws, ads = (layer + "_flow_" + suffix for layer in ("dwd", "dws", "ads"))
    dwd_model = model(client, dwd, "DWD", [("id", "BIGINT", True), ("customer_name", "VARCHAR(128)", False), ("amount", "DECIMAL(18,2)", False), ("updated_at", "DATETIME(6)", False)])
    summary = [("customer_name", "VARCHAR(128)", True), ("total_amount", "DECIMAL(18,2)", False), ("order_count", "BIGINT", False)]
    dws_model = model(client, dws, "DWS", summary)
    ads_model = model(client, ads, "ADS", summary)
    print(json.dumps({"stage": "models-created", "tables": [dwd, dws, ads]}), flush=True)
    _, page = client.request("GET", "/sources")
    source_name = "flow-source-" + suffix
    status, page = post_form(client, page, "/sources", {"name": source_name,
        "jdbcUrl": "jdbc:mysql://demo-mysql:3306/demo_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "username": "demo_reader", "password": "demo_reader_password"})
    checked(status, page, "数据源已保存")
    source_id = id_from_row(page, source_name, "/test")
    _, page = client.request("GET", "/pipelines")
    pipeline_name = "flow-sync-" + suffix
    status, page = post_form(client, page, "/pipelines", {"name": pipeline_name, "sourceId": source_id,
        "sourceTable": "orders", "watermarkColumn": "updated_at", "targetTable": "ods_orders", "transformSql": "", "cronExpression": "0 0 * * * ?"})
    checked(status, page, "任务已保存")
    pipeline_id = id_from_row(page, pipeline_name, "/run")
    dwd_sql = f"INSERT INTO {dwd} SELECT id,customer_name,amount,updated_at FROM ods_orders WHERE id IN (1,2)"
    dwd_id = task(client, dwd, dwd_model, dwd_sql, pipeline=pipeline_id)
    dws_id = task(client, dws, dws_model, f"INSERT INTO {dws} SELECT customer_name,SUM(amount),COUNT(*) FROM {dwd} GROUP BY customer_name", upstream=dwd_id)
    root = task(client, ads, ads_model, f"INSERT INTO {ads} SELECT customer_name,total_amount,order_count FROM {dws}", upstream=dws_id)
    _, page = client.request("GET", f"/transforms/{root}")
    status, page = post_form(client, page, f"/transforms/{root}/run", {})
    checked(status, page, "DolphinScheduler 已接受整条依赖链")
    names = [dwd, dws, ads]
    wait_runs(client, root, names, 1, 300)
    print(json.dumps({"stage": "manual-dag-success", "rootTaskId": int(root)}), flush=True)
    _, page = client.request("GET", "/services")
    slug = "flow-result-" + suffix
    status, page = post_form(client, page, "/services", {"name": slug, "slug": slug,
        "querySql": f"SELECT customer_name,total_amount,order_count FROM {ads} ORDER BY customer_name", "maxRows": "10"})
    checked(status, page, "服务草稿已保存")
    service_id = id_from_row(page, slug, "/publish")
    status, page = post_form(client, page, f"/services/{service_id}/publish", {})
    status, page = post_form(client, page, f"/services/{service_id}/credentials", {})
    key = generated_key(page)
    status, data = client.request("GET", f"/api/data/{slug}", headers={"X-API-Key": key})
    rows = json.loads(data).get("data", [])
    expected = {"Ada": (120.5, 1), "Linus": (88.0, 1)}
    if status != 200 or {row["customer_name"]: (float(row["total_amount"]), int(row["order_count"])) for row in rows} != expected:
        raise SmokeFailure("ADS aggregation did not match demo source")
    _, page = client.request("GET", f"/transforms/{root}")
    status, page = post_form(client, page, f"/transforms/{root}/schedule", {})
    checked(status, page, "整条依赖链的定时调度已启用")
    print(json.dumps({"stage": "timer-enabled-awaiting-trigger", "rootTaskId": int(root)}), flush=True)
    try:
        wait_runs(client, root, names, 2, 420)
    finally:
        _, page = client.request("GET", f"/transforms/{root}")
        status, page = post_form(client, page, f"/transforms/{root}/pause", {})
        checked(status, page, "定时调度已停用")
    print(json.dumps({"stage": "timer-trigger-success-and-paused"}), flush=True)
    before = {int(item["id"]) for item in engine_instances(root)}
    _, edit = client.request("GET", f"/transforms/{dwd_id}/edit")
    status, page = post_form(client, edit, f"/transforms/{dwd_id}", {"name": dwd, "modelId": dwd_model,
        "sqlText": f"INSERT INTO {dwd} SELECT id,customer_name,amount,updated_at FROM missing_flow_{suffix}",
        "cronExpression": "0 */5 * * * ?", "upstreamPipelineIds": pipeline_id})
    checked(status, page, "SQL 加工任务已保存")
    _, page = client.request("GET", f"/transforms/{root}")
    status, page = post_form(client, page, f"/transforms/{root}/run", {})
    checked(status, page, "DolphinScheduler 已接受整条依赖链")
    deadline = time.monotonic() + 300
    failed = False
    while time.monotonic() < deadline:
        fresh = [item for item in engine_instances(root) if int(item["id"]) not in before]
        if fresh and fresh[0].get("state") in (6, "FAILURE", "FAILED"):
            failed = True
            break
        time.sleep(5)
    if not failed:
        raise SmokeFailure("failed upstream workflow did not reach DS FAILURE")
    _, records = runs(client, root)
    if not any(row["task"] == dwd and row["status"] == "FAILED" for row in records):
        raise SmokeFailure("upstream failure missing from platform history")
    if any(sum(row["task"] == name for row in records) != 2 for name in (dws, ads)):
        raise SmokeFailure("downstream ran after upstream failure")
    # Restore the task for further manual trials, keeping failure evidence and the timer paused.
    _, edit = client.request("GET", f"/transforms/{dwd_id}/edit")
    status, page = post_form(client, edit, f"/transforms/{dwd_id}", {"name": dwd, "modelId": dwd_model, "sqlText": dwd_sql,
        "cronExpression": "0 */5 * * * ?", "upstreamPipelineIds": pipeline_id})
    checked(status, page, "SQL 加工任务已保存")
    return {"status": "success", "rootTaskId": int(root), "tables": names,
            "checks": ["pure-ods-sync", "manual-real-dag", "dwd-dws-ads-results", "actual-timer-trigger", "pause", "upstream-failure-gates-downstream"]}


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False), flush=True)
    except Exception as error:
        print(json.dumps({"status": "failed", "reason": str(error) if isinstance(error, SmokeFailure) else type(error).__name__}), flush=True)
        sys.exit(1)
