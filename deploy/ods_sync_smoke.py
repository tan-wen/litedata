#!/usr/bin/env python3
"""Real-environment smoke test for automatic ODS onboarding.

Creates a uniquely named synthetic source table and leaves that fixture in place
for acceptance evidence. It never prints credentials, tokens, or source rows.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import uuid

from smoke import (BASE_URL, POLL_TIMEOUT_SECONDS, Client, SmokeFailure, generated_key,
                   id_from_row, load_dotenv, parse, post_form, require_success)


def mysql_fixture(sql: str) -> None:
    """Run fixture SQL through stdin so neither SQL nor credentials are shell-expanded."""
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "demo-mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot demo_source'],
        input=sql, text=True, encoding="utf-8", errors="strict", capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise SmokeFailure("synthetic source fixture command failed")


def form_inputs(page: str, action: str) -> dict[str, str]:
    form = next((item for item in parse(page).forms if str(item["action"]) == action), None)
    if form is None:
        raise SmokeFailure("expected pipeline form is absent")
    return {str(key): str(value) for key, value in dict(form["inputs"]).items()}


def api_result(client: Client, slug: str, token: str) -> dict[str, object]:
    status, body = client.request("GET", f"/api/data/{slug}?pageNum=1&pageSize=10", headers={"X-API-Key": token})
    if status != 200:
        raise SmokeFailure("published ODS API query failed")
    try:
        result = json.loads(body)
    except json.JSONDecodeError as error:
        raise SmokeFailure("published ODS API returned invalid JSON") from error
    if not isinstance(result, dict):
        raise SmokeFailure("published ODS API returned an unexpected body")
    return result


def await_pipeline(client: Client, pipeline_id: str, after_started: str = "") -> str:
    deadline = time.monotonic() + POLL_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        resource = f"/scheduling/pipeline/{pipeline_id}"
        status, page = client.request("GET", resource)
        if status != 200:
            raise SmokeFailure("pipeline instances became unavailable")
        instances = re.findall(re.escape(resource) + r"/instances/\d+", page)
        if instances:
            status, body = client.request("GET", instances[0] + "/status")
            if status != 200:
                raise SmokeFailure("pipeline instance status became unavailable")
            snapshot = json.loads(body)
            for run in snapshot.get("callbackPage", {}).get("items", []):
                started = run.get("startedAt", "")
                if started > after_started and run.get("status") == "SUCCESS":
                    return started
            if snapshot.get("finished") and snapshot.get("instance", {}).get("state") == "FAILURE":
                raise SmokeFailure("automatic ODS pipeline reported failure")
        time.sleep(5)
    raise SmokeFailure("automatic ODS pipeline did not finish before timeout")


def main() -> dict[str, object]:
    load_dotenv()
    admin_password = os.environ.get("PLATFORM_ADMIN_PASSWORD")
    if not admin_password:
        raise SmokeFailure("PLATFORM_ADMIN_PASSWORD is required")

    unique = uuid.uuid4().hex[:12]
    source_table = f"ods_auto_src_{unique}"
    target_table = f"ods_auto_{unique}"
    source_name = f"ods-auto-source-{unique}"
    pipeline_name = f"ods-auto-pipeline-{unique}"
    service_slug = f"ods-auto-{unique}"
    expected_name = "自动接入😀"
    drift_added = False
    client = Client()

    fixture_sql = f"""
CREATE TABLE `{source_table}` (
  `customer_name` VARCHAR(32) NOT NULL COMMENT '客户名称',
  `OrderID` BIGINT NOT NULL COMMENT '订单标识',
  `event_day` DATE NOT NULL COMMENT '稳定的业务日期',
  `amount` DECIMAL(18,2) NULL COMMENT '金额',
  `updated_at` DATETIME(6) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`OrderID`, `event_day`),
  KEY `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自动 ODS 验收源';
INSERT INTO `{source_table}` (`customer_name`, `OrderID`, `event_day`, `amount`, `updated_at`)
VALUES ('{expected_name}', 88001, CURRENT_DATE(), 123.45,
  DATE_SUB(CAST(CONCAT(DATE_FORMAT(CURRENT_TIMESTAMP(), '%Y-%m-%d %H:%i:%s'), '.123456') AS DATETIME(6)), INTERVAL 2 SECOND));
"""
    mysql_fixture(fixture_sql)
    try:
        status, login = client.request("GET", "/login")
        if status != 200:
            raise SmokeFailure("login page is unavailable")
        status, dashboard = post_form(client, login, "/login", {"username": "admin", "password": admin_password})
        if status != 200 or "数据中台" not in dashboard:
            raise SmokeFailure("login was not accepted")

        status, source_form = client.request("GET", "/sources/new")
        if status != 200:
            raise SmokeFailure("source create page is unavailable")
        status, sources = post_form(client, source_form, "/sources", {
            "name": source_name,
            "jdbcUrl": "jdbc:mysql://demo-mysql:3306/demo_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            "username": "demo_reader",
            "password": "demo_reader_password",
        })
        require_success(status, sources, "数据源已保存")
        source_id = id_from_row(sources, source_name, "/test")
        status, table_list = client.request("GET", f"/pipelines/source-tables?sourceId={source_id}")
        if status != 200 or source_table not in table_list:
            raise SmokeFailure("source-table linkage did not include the synthetic source table")

        status, new_pipeline = client.request("GET", "/pipelines/new")
        if status != 200 or "自动读取结构并创建 ODS" not in new_pipeline:
            raise SmokeFailure("pipeline AUTO onboarding UI is unavailable")
        preview_fields = form_inputs(new_pipeline, "/pipelines")
        preview_fields.update({
            "name": pipeline_name, "sourceId": source_id, "targetMode": "AUTO",
            "sourceTable": source_table, "targetTable": target_table, "watermarkColumn": "updated_at",
            "cronExpression": "0 0 * * * ?", "autoBuckets": "false", "buckets": "3", "replicationNum": "1",
            "distributionType": "HASH", "distributionColumns": "orderid", "partitionType": "AUTO_RANGE",
            "partitionColumn": "event_day", "partitionUnit": "DAY", "wizardStep": "3",
        })
        status, preview = client.request("POST", "/pipelines/preview", preview_fields)
        require_success(status, preview, "第二步：字段映射与键列")
        if "OrderID" not in preview or "orderid" not in preview or "VARCHAR(128)" not in preview or "DATETIME(6)" not in preview:
            raise SmokeFailure("automatic ODS preview did not show the expected field mapping")
        if "AUTO PARTITION BY RANGE" not in preview or "DISTRIBUTED BY HASH(`orderid`) BUCKETS 3" not in preview:
            raise SmokeFailure("automatic ODS preview did not preserve partition and bucket settings")

        create_fields = form_inputs(preview, "/pipelines")
        create_fields.update({
            "name": pipeline_name, "sourceId": source_id, "targetMode": "AUTO", "sourceTable": source_table,
            "watermarkColumn": "updated_at", "cronExpression": "0 0 * * * ?", "autoBuckets": "false",
            "buckets": "3", "replicationNum": "1", "confirmed": "true",
            "distributionType": "HASH", "distributionColumns": "orderid", "partitionType": "AUTO_RANGE",
            "partitionColumn": "event_day", "partitionUnit": "DAY",
        })
        # Browser checkboxes submit repeated names; a dict would silently discard part of a composite key.
        create_payload = [(key, value) for key, value in create_fields.items() if key != "keyColumns"]
        create_payload.extend(("keyColumns", column) for column in ("OrderID", "event_day"))
        status, pipelines = client.request("POST", "/pipelines", create_payload)
        require_success(status, pipelines, "ODS 表、模型与同步管道已创建")
        pipeline_id = id_from_row(pipelines, pipeline_name, "/run")

        status, models = client.request("GET", "/models")
        if status != 200:
            raise SmokeFailure("model list is unavailable after automatic onboarding")
        match=re.search(r'href="/models/(\d+)"[^>]*>'+re.escape(target_table)+r'</a>',models)
        if not match:
            raise SmokeFailure("automatically registered model was absent")
        model_id=match.group(1)

        status, run_page = post_form(client, pipelines, f"/pipelines/{pipeline_id}/run", {})
        require_success(status, run_page, "DolphinScheduler 已接受执行请求")
        first_started = await_pipeline(client, pipeline_id)

        status, service_form = client.request("GET", "/services/new")
        if status != 200:
            raise SmokeFailure("service create page is unavailable")
        status, services = post_form(client, service_form, "/services", {
            "name": f"Auto ODS {unique}", "slug": service_slug,
            "querySql": f"SELECT orderid, customer_name, amount, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f') AS copied_time FROM {target_table} ORDER BY orderid",
        })
        require_success(status, services, "服务草稿已保存")
        service_id = id_from_row(services, service_slug, "/publish")
        status, services = post_form(client, services, f"/services/{service_id}/publish", {})
        require_success(status, services, "发布状态已更新")
        status, credential_page = post_form(client, services, f"/services/{service_id}/credentials", {})
        require_success(status, credential_page, "新凭证仅显示本次")
        token = generated_key(credential_page)
        result = api_result(client, service_slug, token)
        rows = result.get("data")
        if not isinstance(rows, list) or len(rows) != 1:
            raise SmokeFailure("published ODS API did not return one synthetic row")
        row = rows[0]
        if not isinstance(row, dict) or int(row.get("orderid", -1)) != 88001 or str(row.get("amount")) != "123.45" or row.get("customer_name") != expected_name or not str(row.get("copied_time", "")).endswith(".123456"):
            raise SmokeFailure("published ODS API values did not match the synthetic fixture")

        status, replay_page = post_form(client, pipelines, f"/pipelines/{pipeline_id}/run", {})
        require_success(status, replay_page, "DolphinScheduler 已接受执行请求")
        await_pipeline(client, pipeline_id, after_started=first_started)
        replay_rows = api_result(client, service_slug, token).get("data")
        if not isinstance(replay_rows, list) or len(replay_rows) != 1 or replay_rows[0] != row:
            raise SmokeFailure("partitioned ODS replay changed the synthetic row or introduced duplicates")

        mysql_fixture(f"ALTER TABLE `{source_table}` ADD COLUMN `drift_probe` INT NULL;")
        drift_added = True
        status, drift_page = post_form(client, pipelines, f"/pipelines/{pipeline_id}/run", {})
        if status != 200 or "源表结构已变化" not in drift_page or "DolphinScheduler 已接受执行请求" in drift_page:
            raise SmokeFailure("source schema drift was not blocked before scheduling")

        return {"status": "success", "checks": ["source-table-linkage", "auto-preview", "partition-bucket-ddl", "auto-create", "sync", "ods-api", "replay-idempotence", "schema-drift-block"],
                "resources": {"sourceId": int(source_id), "pipelineId": int(pipeline_id), "modelId": int(model_id),
                              "serviceId": int(service_id), "sourceTable": source_table, "targetTable": target_table}}
    finally:
        if drift_added:
            mysql_fixture(f"ALTER TABLE `{source_table}` DROP COLUMN `drift_probe`;")


if __name__ == "__main__":
    started = time.monotonic()
    try:
        outcome = main()
        outcome["elapsedSeconds"] = round(time.monotonic() - started, 2)
        print(json.dumps(outcome, ensure_ascii=False, separators=(",", ":")))
    except Exception as error:
        print(json.dumps({"status": "failed", "reason": type(error).__name__,
                          "elapsedSeconds": round(time.monotonic() - started, 2)}, ensure_ascii=False, separators=(",", ":")))
        sys.exit(1)
