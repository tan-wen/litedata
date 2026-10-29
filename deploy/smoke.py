#!/usr/bin/env python3
"""Host-side smoke test for the fully deployed LiteData development stack."""
from __future__ import annotations

import json
import os
import re
import sys
import time
import uuid
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import HTTPCookieProcessor, Request, build_opener


BASE_URL = os.environ.get("PLATFORM_URL", "http://localhost:8080/")
ROOT = Path(__file__).resolve().parents[1]
POLL_TIMEOUT_SECONDS = 300


def load_dotenv() -> None:
    path = ROOT / ".env"
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict[str, object]] = []
        self.rows: list[dict[str, object]] = []
        self.codes: list[str] = []
        self._form: dict[str, object] | None = None
        self._row: dict[str, object] | None = None
        self._cell: list[str] | None = None
        self._code: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "form":
            self._form = {"action": values.get("action", ""), "method": values.get("method", "get").lower(), "inputs": {}}
        elif tag == "input" and self._form is not None and values.get("name"):
            inputs = self._form["inputs"]
            assert isinstance(inputs, dict)
            if values.get("type", "").lower() not in ("checkbox", "radio") or "checked" in values:
                inputs[values["name"]] = values.get("value", "")
        elif tag == "tr":
            self._row = {"cells": [], "actions": []}
        elif tag in ("td", "th") and self._row is not None:
            self._cell = []
        elif tag == "code":
            self._code = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "form" and self._form is not None:
            self.forms.append(self._form)
            if self._row is not None:
                actions = self._row["actions"]
                assert isinstance(actions, list)
                actions.append(str(self._form["action"]))
            self._form = None
        elif tag in ("td", "th") and self._cell is not None and self._row is not None:
            cells = self._row["cells"]
            assert isinstance(cells, list)
            cells.append(" ".join("".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            self.rows.append(self._row)
            self._row = None
        elif tag == "code" and self._code is not None:
            self.codes.append("".join(self._code).strip())
            self._code = None

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)
        if self._code is not None:
            self._code.append(data)


class SmokeFailure(RuntimeError):
    pass


class Client:
    def __init__(self) -> None:
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))

    def request(self, method: str, path: str, fields: dict[str, str] | list[tuple[str, str]] | None = None, headers: dict[str, str] | None = None) -> tuple[int, str]:
        target = urljoin(BASE_URL, path)
        data = urlencode(fields or {}).encode() if method == "POST" else None
        request = Request(target, data=data, headers=headers or {}, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")
        except URLError as error:
            raise SmokeFailure("platform HTTP endpoint is unavailable") from error


def parse(html: str) -> PageParser:
    parser = PageParser()
    parser.feed(html)
    return parser


def post_form(client: Client, page: str, action: str, fields: dict[str, str]) -> tuple[int, str]:
    parsed = parse(page)
    form = next((item for item in parsed.forms if str(item["action"]) == action), None)
    if form is None:
        raise SmokeFailure("expected server form is absent")
    form_inputs = dict(form["inputs"])
    form_inputs.update(fields)
    return client.request("POST", action, {str(k): str(v) for k, v in form_inputs.items()})


def require_success(status: int, html: str, expected: str) -> None:
    if status != 200 or "alert-danger" in html or expected not in html:
        raise SmokeFailure("server did not confirm the requested operation")


def id_from_row(page: str, marker: str, suffix: str) -> str:
    for row in parse(page).rows:
        cells = row["cells"]
        actions = row["actions"]
        assert isinstance(cells, list) and isinstance(actions, list)
        if marker in " ".join(map(str, cells)):
            for action in actions:
                match = re.search(r"/(\d+)" + re.escape(suffix) + r"$", str(action))
                if match:
                    return match.group(1)
    raise SmokeFailure("created resource was not present in its server-rendered table")


def generated_key(html: str) -> str:
    for value in parse(html).codes:
        if value.startswith("ld_"):
            return value
    raise SmokeFailure("credential rotation did not return a one-time key")


def resource_id(page: str, prefix: str, suffix: str) -> str:
    for form in parse(page).forms:
        match = re.fullmatch(re.escape(prefix) + r"/(\d+)/" + re.escape(suffix), str(form["action"]))
        if match:
            return match.group(1)
    raise SmokeFailure("created resource detail was absent")


def create_model(client: Client, table: str, layer: str, fields: list[tuple[str, str, bool]]) -> str:
    status, page = client.request("GET", "/models/new")
    if status != 200:
        raise SmokeFailure("model create page is unavailable")
    values = {"tableName": table, "name": table, "subjectArea": "冒烟验证", "layer": layer, "buckets": "1",
        "modelType": "UNIQUE", "replicationNum": "1", "distributionType": "HASH", "autoBuckets": "false",
        "distributionColumns": ",".join(name for name, _, key in fields if key), "partitionType": "NONE"}
    for index, (name, data_type, key) in enumerate(fields):
        for prop, value in {"name": name, "dataType": data_type, "keyColumn": str(key).lower(), "nullable": "false", "comment": "", "standard": ""}.items():
            values[f"fields[{index}].{prop}"] = value
    status, page = post_form(client, page, "/models", values)
    require_success(status, page, "逻辑模型已保存")
    model_id = resource_id(page, "/models", "physicalize")
    status, page = post_form(client, page, f"/models/{model_id}/physicalize", {"confirmed": "true"})
    require_success(status, page, "Doris 表已创建")
    return model_id


def create_transform(client: Client, name: str, model_id: str, sql: str, pipeline_id: str) -> str:
    status, page = client.request("GET", "/transforms/new")
    if status != 200:
        raise SmokeFailure("transform create page is unavailable")
    status, page = post_form(client, page, "/transforms", {"name": name, "modelId": model_id, "sqlText": sql,
        "cronExpression": "0 0 * * * ?", "upstreamPipelineIds": pipeline_id})
    require_success(status, page, "数据加工已保存")
    return resource_id(page, "/transforms", "run")


def api_query(client: Client, slug: str, token: str) -> tuple[int, str]:
    return client.request("GET", f"/api/data/{slug}?customerId=1&pageNum=1&pageSize=100", headers={"X-API-Key": token})


def main() -> dict[str, object]:
    load_dotenv()
    password = os.environ.get("PLATFORM_ADMIN_PASSWORD")
    if not password:
        raise SmokeFailure("PLATFORM_ADMIN_PASSWORD is required in .env or environment")
    client = Client()
    unique = uuid.uuid4().hex[:12]
    source_name = f"smoke-source-{unique}"
    pipeline_name = f"smoke-pipeline-{unique}"
    transform_name = f"smoke-transform-{unique}"
    slug = f"smoke-orders-{unique}"
    ods_table = f"ods_smoke_{unique}"
    ads_table = f"ads_smoke_{unique}"

    status, login = client.request("GET", "/login")
    if status != 200:
        raise SmokeFailure("login page is unavailable")
    status, dashboard = post_form(client, login, "/login", {"username": "admin", "password": password})
    if status != 200 or "数据中台" not in dashboard:
        raise SmokeFailure("login was not accepted")

    order_fields = [("id", "BIGINT", True), ("customer_name", "VARCHAR(128)", False),
        ("amount", "DECIMAL(18,2)", False), ("updated_at", "DATETIME(6)", False)]
    ods_model_id = create_model(client, ods_table, "ODS", order_fields)
    ads_model_id = create_model(client, ads_table, "ADS", order_fields)

    status, sources = client.request("GET", "/sources")
    if status != 200 or 'href="/sources/new"' not in sources:
        raise SmokeFailure("source list does not provide the create page")
    status, source_form = client.request("GET", "/sources/new")
    if status != 200:
        raise SmokeFailure("source create page is unavailable after login")
    status, sources = post_form(client, source_form, "/sources", {
        "name": source_name,
        "jdbcUrl": "jdbc:mysql://demo-mysql:3306/demo_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "username": "demo_reader",
        "password": "demo_reader_password",
    })
    require_success(status, sources, "数据源已保存")
    source_id = id_from_row(sources, source_name, "/test")
    status, sources = post_form(client, sources, f"/sources/{source_id}/test", {})
    require_success(status, sources, "连接测试成功")

    status, pipelines = client.request("GET", "/pipelines")
    if status != 200 or 'href="/pipelines/new"' not in pipelines:
        raise SmokeFailure("pipeline list does not provide the create page")
    status, pipeline_form = client.request("GET", "/pipelines/new")
    if status != 200:
        raise SmokeFailure("pipeline create page is unavailable")
    status, pipelines = post_form(client, pipeline_form, "/pipelines", {
        "name": pipeline_name, "sourceId": source_id, "sourceTable": "orders", "watermarkColumn": "updated_at",
        "targetMode": "EXISTING", "targetTable": ods_table,
        "cronExpression": "0 0 * * * ?",
    })
    require_success(status, pipelines, "ODS 同步管道已保存")
    pipeline_id = id_from_row(pipelines, pipeline_name, "/run")
    transform_id = create_transform(client, transform_name, ads_model_id,
        f"INSERT INTO {ads_table} SELECT id,customer_name,amount,updated_at FROM {ods_table}", pipeline_id)
    status, transform_page = client.request("GET", f"/transforms/{transform_id}")
    if status != 200:
        raise SmokeFailure("transform detail page is unavailable")
    status, pipelines = post_form(client, transform_page, f"/transforms/{transform_id}/run", {})
    require_success(status, pipelines, "DolphinScheduler 已接受整条依赖链")

    deadline = time.monotonic() + POLL_TIMEOUT_SECONDS
    run_succeeded = False
    while time.monotonic() < deadline:
        status, pipelines = client.request("GET", f"/transforms/{transform_id}")
        if status != 200:
            raise SmokeFailure("transform status page became unavailable")
        for row in parse(pipelines).rows:
            cells = " ".join(map(str, row["cells"]))
            if transform_name in cells and ("FAILED" in cells or "失败" in cells):
                raise SmokeFailure("DolphinScheduler run reported failure")
            if transform_name in cells and ("SUCCESS" in cells or "成功" in cells):
                run_succeeded = True
                break
        if run_succeeded:
            break
        time.sleep(5)
    if not run_succeeded:
        raise SmokeFailure("pipeline did not report successful completion before timeout")

    status, services = client.request("GET", "/services")
    if status != 200 or 'href="/services/new"' not in services:
        raise SmokeFailure("service list does not provide the create page")
    status, service_form = client.request("GET", "/services/new")
    if status != 200:
        raise SmokeFailure("service create page is unavailable")
    status, services = post_form(client, service_form, "/services", {
        "name": f"Smoke orders {unique}", "slug": slug,
        "querySql": f"SELECT id, customer_name, amount, updated_at FROM {ads_table} WHERE id = :customerId ORDER BY id",
    })
    require_success(status, services, "服务草稿已保存")
    service_id = id_from_row(services, slug, "/publish")
    status, services = post_form(client, services, f"/services/{service_id}/publish", {})
    require_success(status, services, "发布状态已更新")
    status, services = post_form(client, services, f"/services/{service_id}/credentials", {})
    require_success(status, services, "新凭证仅显示本次")
    first_key = generated_key(services)
    status, result = api_query(client, slug, first_key)
    response = json.loads(result)
    if status != 200 or response.get("data", [{}])[0].get("customer_name") != "Ada" or {key: response.get(key) for key in ("count", "pageNum", "pageSize", "hasMore")} != {"count": 1, "pageNum": 1, "pageSize": 100, "hasMore": False}:
        raise SmokeFailure("published API did not return Ada for id=1")

    status, services = client.request("GET", "/services")
    status, services = post_form(client, services, f"/services/{service_id}/credentials", {})
    require_success(status, services, "新凭证仅显示本次")
    second_key = generated_key(services)
    old_status, _ = api_query(client, slug, first_key)
    if old_status != 401:
        raise SmokeFailure("rotated credential remained valid")
    status, result = api_query(client, slug, second_key)
    if status != 200 or json.loads(result).get("data", [{}])[0].get("customer_name") != "Ada":
        raise SmokeFailure("rotated credential cannot query the published API")
    return {"status": "success", "counts": {"models": 2, "sources": 1, "pipelines": 1, "transforms": 1, "services": 1, "apiRows": 1},
            "resources": {"odsModelId": int(ods_model_id), "adsModelId": int(ads_model_id), "pipelineId": int(pipeline_id), "transformId": int(transform_id)}}


if __name__ == "__main__":
    started = time.monotonic()
    try:
        outcome = main()
        outcome["elapsedSeconds"] = round(time.monotonic() - started, 2)
        print(json.dumps(outcome, ensure_ascii=False, separators=(",", ":")))
    except Exception as error:
        print(json.dumps({"status": "failed", "reason": type(error).__name__, "elapsedSeconds": round(time.monotonic() - started, 2)}, ensure_ascii=False, separators=(",", ":")))
        sys.exit(1)
