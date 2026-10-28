"""Run the isolated chemical-park fixture through the real local LiteData stack.

Only new, uniquely prefixed demo resources are created. Secrets stay in memory;
the report contains resource IDs, configuration evidence, and synthetic results.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import secrets
import subprocess
import sys
import time
from decimal import Decimal
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin, urlparse
from urllib.request import HTTPCookieProcessor, Request, build_opener

from scenario import build_scenario, write_artifacts
from dashboard import render_dashboard

ROOT = Path(__file__).resolve().parents[2]
# The fixture uses DATETIME wall-clock values, not TIMESTAMP instants. Let each
# JDBC runtime use its local calendar so DataX's local formatter preserves them.
SOURCE_JDBC = "jdbc:mysql://demo-mysql:3306/demo_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=LOCAL"
sys.path.insert(0, str(ROOT / "deploy"))
from smoke import PageParser, generated_key, load_dotenv  # noqa: E402


class DemoFailure(RuntimeError):
    pass


class DemoParser(PageParser):
    def __init__(self):
        super().__init__()
        self.pre = []
        self._pre = None

    def handle_starttag(self, tag, attrs):
        super().handle_starttag(tag, attrs)
        values = dict(attrs)
        if tag == "a" and self._row is not None and values.get("href"):
            self._row["actions"].append(values["href"])
        if tag == "pre" and self._pre is None:
            self._pre = []

    def handle_data(self, data):
        super().handle_data(data)
        if self._pre is not None:
            self._pre.append(data)

    def handle_endtag(self, tag):
        if tag == "pre" and self._pre is not None:
            self.pre.append("".join(self._pre))
            self._pre = None
        super().handle_endtag(tag)


def parse(page):
    parser = DemoParser()
    parser.feed(page)
    return parser


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/") + "/"
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        self.last_path = ""

    def request(self, method, path, fields=None, headers=None):
        data = urlencode(fields or {}, doseq=True).encode() if method == "POST" else None
        request = Request(urljoin(self.base, path), data=data, method=method, headers=headers or {})
        try:
            with self.opener.open(request, timeout=45) as response:
                self.last_path = urlparse(response.geturl()).path
                return response.status, response.read().decode("utf-8")
        except HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")
        except (URLError, TimeoutError) as error:
            raise DemoFailure("平台暂不可访问，请先启动开发环境") from error

    def post(self, page, action, fields=None):
        form = next((f for f in parse(page).forms if f["action"] == action), None)
        if form is None:
            raise DemoFailure("页面缺少预期表单：" + action)
        values = dict(form["inputs"])
        values.update(fields or {})
        return self.request("POST", action, values)


def checked(response, expected):
    status, page = response
    if status != 200 or expected not in page:
        raise DemoFailure("平台未确认操作：" + expected)
    return page


def identifier(page, marker, resource, operation):
    for row in parse(page).rows:
        if marker not in " ".join(row["cells"]):
            continue
        for action in row["actions"]:
            found = re.fullmatch(r"/" + resource + r"/(\d+)/" + operation, action)
            if found:
                return int(found.group(1))
    raise DemoFailure("新资源未出现在列表中：" + resource)


def docker(service, command, input_text="", timeout=90):
    result = subprocess.run(["docker", "compose", "exec", "-T", service, *command], cwd=ROOT,
                            input=input_text.encode("utf-8"), capture_output=True, timeout=timeout)
    if result.returncode:
        # Container errors can contain JDBC configuration; do not copy them into reports.
        raise DemoFailure("容器步骤失败：" + service + "（请在本机检查已脱敏日志）")
    return result.stdout.decode("utf-8")


def source_sql(sql):
    return docker("demo-mysql", ["sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 --user=root demo_source'], sql)


def warehouse(sql):
    script = """import os,json,pymysql
c=pymysql.connect(host='doris',port=9030,user=os.environ['DORIS_USER'],password=os.environ['DORIS_PASSWORD'],database='warehouse',cursorclass=pymysql.cursors.DictCursor)
with c.cursor() as q:
 q.execute(SQL)
 print(json.dumps(q.fetchall(),default=str))
c.close()
""".replace("q.execute(SQL)", "q.execute(" + repr(sql) + ")")
    return json.loads(docker("dolphinscheduler", ["python3", "-"], script))


class Demo:
    def __init__(self, args):
        self.args = args
        self.client = Client(args.url)
        self.manifest = None
        self.report = {"status": "running", "prefix": args.prefix, "platform_url": args.url,
                       "resources": {k: {} for k in ("models", "pipelines", "transforms", "metrics", "qualities", "services")},
                       "stages": [], "results": {}}
        self.keys = {}
        self.metric_sql = {}
        self.output = args.output or ROOT / "output" / "chemical-park" / args.prefix
        self.output.mkdir(parents=True, exist_ok=False)

    def record(self, stage, status="passed", **details):
        self.report["stages"].append({"stage": stage, "status": status, "details": details})
        self.save()
        print(json.dumps({"stage": stage, "status": status, **details}, ensure_ascii=False), flush=True)

    def save(self):
        (self.output / "report.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    def model(self, definition):
        _, page = self.client.request("GET", "/models/new")
        fields = {k: definition[k] for k in ("tableName", "name", "subjectArea", "layer", "buckets")}
        fields.update(modelType="UNIQUE", replicationNum="1", distributionType="HASH", autoBuckets="false",
            distributionColumns=",".join(field["name"] for field in definition["fields"] if field.get("keyColumn")), partitionType="NONE")
        fields["name"] = self.args.prefix + " " + fields["name"]
        for index, field in enumerate(definition["fields"]):
            for key in ("name", "dataType", "keyColumn", "nullable", "comment", "standard"):
                value = field.get(key, "" if key in ("comment", "standard") else False)
                fields[f"fields[{index}].{key}"] = str(value).lower() if isinstance(value, bool) else value
        page = checked(self.client.post(page, "/models", fields), "逻辑模型已保存")
        found = re.fullmatch(r"/models/(\d+)", self.client.last_path)
        if not found:
            raise DemoFailure("模型详情未返回")
        model_id = int(found.group(1))
        checked(self.client.post(page, f"/models/{model_id}/physicalize", {"confirmed": "true"}), "Doris 表已创建")
        self.report["resources"]["models"][definition["key"]] = model_id

    def quality(self, definition):
        _, page = self.client.request("GET", "/quality/new")
        fields = {k: definition[k] for k in ("name", "tableName", "owner", "description")}
        fields.update(name=self.args.prefix + " " + fields["name"], cronExpression="", timeColumn="", windowHours=24, timeoutSeconds=30)
        if definition["sourceKind"] == "source":
            fields["sourceId"] = self.report["resources"]["source"]
        for index, rule in enumerate(definition["rules"]):
            for key, value in rule.items():
                fields[f"rules[{index}].{key}"] = value
        page = checked(self.client.post(page, "/quality", fields), "质量任务已保存")
        task_id = identifier(page, fields["name"], "quality", "edit")
        self.report["resources"]["qualities"][definition["key"]] = task_id

    def run_quality(self, key, expected):
        task_id = self.report["resources"]["qualities"][key]
        _, page = self.client.request("GET", f"/quality/{task_id}")
        status, page = self.client.post(page, f"/quality/{task_id}/run")
        path = self.client.last_path
        if status != 200 or not re.fullmatch(r"/quality/runs/\d+", path):
            raise DemoFailure("质量检测未产生运行记录")
        labels = {"通过": "PASSED", "异常": "FAILED", "执行失败": "ERROR", "警告": "WARNING", "扫描中": "RUNNING"}
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            _, page = self.client.request("GET", path)
            match = re.search(r'<div class="fg-2 small">状态</div>\s*<span[^>]*>([^<]+)</span>', page)
            state = labels.get(match.group(1), "RUNNING") if match else "RUNNING"
            if state != "RUNNING":
                if state != expected:
                    raise DemoFailure("质量检测结果与预期不一致：" + key + " / " + state)
                rule_rows = [r["cells"] for r in parse(page).rows if len(r["cells"]) == 7 and re.match(r"^\d+ / ", r["cells"][4])]
                anomalies = sum(int(row[4].split(" / ")[0]) for row in rule_rows)
                if not rule_rows or anomalies != (1 if expected == "FAILED" else 0):
                    raise DemoFailure("质量异常条数与预置场景不一致")
                return {"status": state, "path": path, "anomalyCount": anomalies}
            time.sleep(1)
        raise DemoFailure("质量检测等待超时")

    def run_chain(self, number):
        root = self.report["resources"]["transforms"]["ads_park_7d"]
        _, page = self.client.request("GET", f"/transforms/{root}")
        page = checked(self.client.post(page, f"/transforms/{root}/run"), "DolphinScheduler 已接受整条依赖链")
        names = {self.args.prefix + " " + t["name"] for t in self.manifest["transforms"]}
        deadline = time.monotonic() + self.args.timeout
        last_progress = 0
        while time.monotonic() < deadline:
            _, page = self.client.request("GET", f"/transforms/{root}")
            rows = [r["cells"] for r in parse(page).rows]
            runs = [r for r in rows if len(r) == 6 and r[0].isdigit() and r[3] in ("RUNNING", "SUCCESS", "FAILED")]
            if any(r[3] == "FAILED" for r in runs):
                raise DemoFailure("依赖链有节点失败，请按资源 ID 查看平台运行记录")
            completed = {name: sum(r[1].split(" / ")[0] == name and r[3] == "SUCCESS" for r in runs) for name in names}
            if all(value >= number for value in completed.values()):
                self.record("依赖链运行 " + str(number), rootTaskId=root, completed=completed)
                return
            if time.monotonic() - last_progress > 25:
                print(json.dumps({"stage": "waiting-for-worker", "run": number, "completedSqlNodes": sum(v >= number for v in completed.values())}), flush=True)
                last_progress = time.monotonic()
            time.sleep(3)
        raise DemoFailure("等待 DataX / SQL 节点回调超时")

    def counts(self, expected_dwd):
        expected = {"ods_enterprise": 3, "ods_utility": 24, "ods_production": 24,
                    "dwd_utility_day": expected_dwd, "dws_enterprise_7d": 3, "ads_park_7d": 1}
        counts = {}
        for model in self.manifest["models"]:
            value = int(warehouse(f"SELECT COUNT(*) n FROM `{model['tableName']}`")[0]["n"])
            counts[model["key"]] = value
        if counts != expected:
            raise DemoFailure("分层行数对账未通过")
        self.check_datetime_parity()
        return counts

    def check_datetime_parity(self):
        model = next(m for m in self.manifest["models"] if m["key"] == "ods_utility")
        observed = warehouse(f"SELECT record_id,stat_date,period_end FROM `{model['tableName']}` ORDER BY record_id")
        expected = next(t for t in self.manifest["source_tables"] if t["key"] == "utility")["rows"]
        wanted = {int(r["record_id"]): (r["stat_date"], r["period_end"]) for r in expected}
        actual = {int(r["record_id"]): (str(r["stat_date"]), str(r["period_end"])) for r in observed}
        if actual != wanted:
            raise DemoFailure("DataX 未保留 DATETIME 钟面时间，请核对示例源 JDBC 的 LOCAL 日历配置")

    def create_metrics(self):
        for metric in self.manifest["metrics"]:
            _, page = self.client.request("GET", "/metrics/new?" + urlencode({"type": metric["type"]}))
            fields = {k: v for k, v in metric.items() if k not in ("key", "modelKey", "baseMetricKey")}
            fields["dimensions"] = ",".join(metric["dimensions"]) if isinstance(metric["dimensions"], list) else metric["dimensions"]
            if metric.get("modelKey"):
                fields["modelId"] = self.report["resources"]["models"][metric["modelKey"]]
            if metric.get("baseMetricKey") and metric["type"] == "DERIVED":
                fields["baseMetricId"] = self.report["resources"]["metrics"][metric["baseMetricKey"]]
            page = checked(self.client.post(page, "/metrics", fields), "指标已保存")
            metric_id = identifier(page, metric["code"], "metrics", "edit")
            self.report["resources"]["metrics"][metric["key"]] = metric_id
            _, detail = self.client.request("GET", f"/metrics/{metric_id}")
            blocks = parse(detail).pre
            if not blocks or not blocks[0].startswith("SELECT "):
                raise DemoFailure("指标未返回保存的 SQL 预览")
            self.metric_sql[metric["key"]] = blocks[0]

    def create_services(self):
        for service in self.manifest["services"]:
            _, page = self.client.request("GET", "/services/new")
            sql = self.metric_sql[service["metricKey"]] if service.get("metricKey") else service["querySql"]
            fields = {"name": self.args.prefix + " " + service["name"], "slug": service["slug"], "querySql": sql}
            page = checked(self.client.post(page, "/services", fields), "服务草稿已保存")
            service_id = identifier(page, service["slug"], "services", "publish")
            page = checked(self.client.post(page, f"/services/{service_id}/publish"), "发布状态已更新")
            page = checked(self.client.post(page, f"/services/{service_id}/credentials"), "新凭证仅显示本次")
            self.keys[service["key"]] = generated_key(page)
            self.report["resources"]["services"][service["key"]] = service_id

    def query(self, key, params=None, authenticated=True):
        service = next(s for s in self.manifest["services"] if s["key"] == key)
        path = "/api/data/" + service["slug"] + "?" + urlencode({"pageNum": 1, "pageSize": 100, **(params or {})})
        status, data = self.client.request("GET", path, headers={"X-API-Key": self.keys[key]} if authenticated else {})
        if not authenticated:
            if status != 401:
                raise DemoFailure("数据服务未拒绝无凭据调用")
            return []
        if status != 200:
            raise DemoFailure("受控数据服务调用未成功：" + key)
        body = json.loads(data)
        if body.get("hasMore"):
            raise DemoFailure("演示结果超过单页上限")
        return body["data"]

    def check_results(self):
        result = {"park": self.query("park_7d"), "enterprises": self.query("enterprise_7d"), "daily": self.query("daily_all"),
                  "trace": self.query("daily_trace", {"enterpriseId": 3, "statDate": self.manifest["expected"]["zero_production_row"]["stat_date"]}),
                  "metricsteam": self.query("metric_steam_7d_service")}
        if len(result["park"]) != 1 or len(result["enterprises"]) != 3 or len(result["daily"]) != 24:
            raise DemoFailure("应用 API 行数不符合预期")
        expected = self.manifest["expected"]

        def date_text(value):
            return str(value)[:10]

        def datetime_text(value):
            return str(value).replace("T", " ").removesuffix("Z")

        def equal_decimal(actual, wanted, message):
            if abs(Decimal(str(actual)) - Decimal(str(wanted))) > Decimal("0.000002"):
                raise DemoFailure(message)

        park = result["park"][0]
        expected_park = expected["park7d_row"]
        for field in ("park_id", "enterprise_count", "valid_days"):
            if int(park.get(field, -1)) != int(expected_park[field]):
                raise DemoFailure("园区汇总身份或覆盖范围不一致：" + field)
        if date_text(park.get("as_of_date")) != expected_park["as_of_date"]:
            raise DemoFailure("园区汇总日期不一致")
        for field in ("steam_tons", "electricity_kwh", "water_m3", "production_tons", "steam_per_ton", "kwh_per_ton"):
            if field not in park:
                raise DemoFailure("园区汇总缺少字段：" + field)
            if abs(Decimal(str(park[field])) - Decimal(str(expected_park[field]))) > Decimal("0.000002"):
                raise DemoFailure("园区汇总指标对账失败：" + field)

        daily = {}
        for row in result["daily"]:
            record_id = int(row.get("record_id", -1))
            if record_id in daily:
                raise DemoFailure("日明细存在重复 record_id")
            daily[record_id] = row
        if set(daily) != {int(row["record_id"]) for row in expected["dwd_final_rows"]}:
            raise DemoFailure("日明细记录集合不符合纠错后的预期")
        for wanted in expected["dwd_final_rows"]:
            actual = daily[int(wanted["record_id"])]
            for field in ("enterprise_id", "record_id"):
                if int(actual.get(field, -1)) != int(wanted[field]):
                    raise DemoFailure("日明细身份字段不一致：" + field)
            for field in ("enterprise_name", "product_code"):
                if actual.get(field) != wanted[field]:
                    raise DemoFailure("日明细企业元数据不一致：" + field)
            if date_text(actual.get("stat_date")) != wanted["stat_date"] or datetime_text(actual.get("period_end")) != wanted["period_end"]:
                raise DemoFailure("日明细业务时间不一致")
            for field in ("steam_tons", "electricity_kwh", "water_m3", "production_tons"):
                equal_decimal(actual.get(field), wanted[field], "日明细度量不一致：" + field)

        enterprises = {int(r["enterprise_id"]): r for r in result["enterprises"]}
        if len(enterprises) != len(result["enterprises"]):
            raise DemoFailure("企业七日结果存在重复企业")
        for wanted in expected["enterprise7d_rows"]:
            actual = enterprises.get(int(wanted["enterprise_id"]))
            if actual is None or int(actual["valid_days"]) != 7:
                raise DemoFailure("企业七日覆盖率对账失败")
            if actual.get("enterprise_name") != wanted["enterprise_name"] or actual.get("product_code") != wanted["product_code"]:
                raise DemoFailure("企业七日元数据不一致")
            for field in ("steam_tons", "electricity_kwh", "water_m3", "production_tons", "steam_per_ton", "kwh_per_ton"):
                equal_decimal(actual.get(field), wanted[field], "企业七日指标与源行计算不一致：" + field)
        if len(result["metricsteam"]) != 3 or {int(r["enterprise_id"]) for r in result["metricsteam"]} != set(enterprises):
            raise DemoFailure("复合指标服务未返回完整企业集合")
        for row in result["metricsteam"]:
            if abs(Decimal(str(row["metric_value"])) - Decimal(str(enterprises[int(row["enterprise_id"])]["steam_per_ton"]))) > Decimal("0.000002"):
                raise DemoFailure("指标 SQL 与 DWS 加权口径不一致")
        zero = expected["zero_production_row"]
        trace = result["trace"][0] if len(result["trace"]) == 1 else None
        if trace is None or int(trace.get("enterprise_id", -1)) != int(zero["enterprise_id"]) or date_text(trace.get("stat_date")) != zero["stat_date"] or Decimal(str(trace.get("production_tons"))) != Decimal(str(zero["production_tons"])) or trace.get("steam_per_ton") is not None or trace.get("kwh_per_ton") is not None:
            raise DemoFailure("零产量场景未返回 NULL 比率")
        self.query("park_7d", authenticated=False)
        self.report["results"] = result

    def execute(self):
        clock = warehouse("SELECT CURRENT_DATE AS as_of_date, CURRENT_TIMESTAMP AS warehouse_time")[0]
        self.manifest = build_scenario(self.args.prefix, dt.date.fromisoformat(str(clock["as_of_date"])))
        self.report["as_of"] = self.manifest["as_of"]
        self.report["warehouse_time"] = clock["warehouse_time"]
        write_artifacts(self.manifest, self.output)
        _, page = self.client.request("GET", "/login")
        checked(self.client.post(page, "/login", {"username": "admin", "password": os.environ["PLATFORM_ADMIN_PASSWORD"]}), "工作台")
        status, page = self.client.request("GET", "/metrics/new")
        if status != 200 or "指标编码" not in page:
            raise DemoFailure("平台版本缺少指标模块，请先构建并更新 platform 服务")
        source_sql(self.manifest["seed_sql"])
        self.record("生成独立模拟源数据", rowCounts=self.manifest["expected"]["source_count"])
        _, page = self.client.request("GET", "/sources/new")
        page = checked(self.client.post(page, "/sources", {"name": self.args.prefix + " 化工园区业务源", "jdbcUrl": SOURCE_JDBC, "username": "demo_reader", "password": "demo_reader_password"}), "数据源已保存")
        source_id = identifier(page, self.args.prefix, "sources", "test")
        self.report["resources"]["source"] = source_id
        checked(self.client.post(page, f"/sources/{source_id}/test"), "连接测试成功")
        for model in self.manifest["models"]:
            self.model(model)
        for pipeline in self.manifest["pipelines"]:
            _, page = self.client.request("GET", "/pipelines/new")
            target = next(m["tableName"] for m in self.manifest["models"] if m["key"] == pipeline["targetModel"])
            fields = {k: pipeline[k] for k in ("sourceTable", "watermarkColumn", "cronExpression")}
            fields.update(name=self.args.prefix + " " + pipeline["name"], sourceId=source_id, targetTable=target)
            page = checked(self.client.post(page, "/pipelines", fields), "ODS 同步管道已保存")
            self.report["resources"]["pipelines"][pipeline["key"]] = identifier(page, fields["name"], "pipelines", "run")
        for task in self.manifest["transforms"]:
            _, page = self.client.request("GET", "/transforms/new")
            fields = {"name": self.args.prefix + " " + task["name"], "modelId": self.report["resources"]["models"][task["targetModel"]],
                      "sqlText": task["sqlText"], "cronExpression": task["cronExpression"],
                      "upstreamPipelineIds": [self.report["resources"]["pipelines"][key] for key in task["upstreamPipelines"]],
                      "upstreamTaskIds": [self.report["resources"]["transforms"][key] for key in task["upstreamTasks"]]}
            checked(self.client.post(page, "/transforms", fields), "数据加工已保存")
            self.report["resources"]["transforms"][task["key"]] = int(self.client.last_path.rsplit("/", 1)[-1])
        for quality in self.manifest["qualities"]:
            self.quality(quality)
        initial = self.run_quality("source_utility_quality", "FAILED")
        self.record("检测到预置源端异常", "expected_anomaly", quality=initial)
        self.run_chain(1)
        self.record("初次分层对账", counts=self.counts(23), quality=self.run_quality("dwd_utility_quality", "PASSED"))
        source_sql(self.manifest["correction_sql"] + ";\n")
        self.run_chain(2)
        self.record("修正源记录并增量重跑", counts=self.counts(24), sourceQuality=self.run_quality("source_utility_quality", "PASSED"), dwdQuality=self.run_quality("dwd_utility_quality", "PASSED"))
        self.run_chain(3)
        self.record("重复运行保持幂等", counts=self.counts(24))
        for fields in ({"sourceId": source_id}, {}):
            _, page = self.client.request("GET", "/metadata/collect")
            checked(self.client.post(page, "/metadata/collect", fields), "采集完成")
        self.create_metrics()
        self.create_services()
        self.check_results()
        metric_id = self.report["resources"]["metrics"]["metric_steam_per_ton_7d"]
        status, page = self.client.request("GET", f"/metrics/{metric_id}/lineage")
        if status != 200 or self.manifest["source_tables"][1]["tableName"] + ".steam_tons" not in page:
            raise DemoFailure("复合指标未追溯到模拟源蒸汽字段")
        self.report["lineage_path"] = f"/metrics/{metric_id}/lineage"
        self.record("指标、源字段血缘和 API 对账通过", metricCount=len(self.manifest["metrics"]), serviceCount=len(self.manifest["services"]), unauthorizedStatus=401)
        self.report["status"] = "success"
        self.save()
        (self.output / "dashboard.html").write_text(render_dashboard(self.report, self.manifest), encoding="utf-8")
        return self.report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", default="cp_" + dt.datetime.now().strftime("%m%d%H%M") + secrets.token_hex(2))
    parser.add_argument("--url", default=os.environ.get("PLATFORM_URL", "http://localhost:8080/"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    # Validate names before creating directories, connecting, or writing source tables.
    build_scenario(args.prefix, dt.date(2026, 10, 24))
    load_dotenv()
    demo = Demo(args)
    try:
        result = demo.execute()
        print(json.dumps({"status": result["status"], "prefix": args.prefix, "output": str(demo.output), "lineage": result["lineage_path"]}, ensure_ascii=False))
    except Exception as error:
        demo.report["status"] = "failed"
        demo.report["error"] = str(error) if isinstance(error, DemoFailure) else type(error).__name__
        demo.save()
        print(json.dumps({"status": "failed", "prefix": args.prefix, "reason": demo.report["error"], "report": str(demo.output / "report.json")}, ensure_ascii=False), flush=True)
        return 1
    finally:
        demo.keys.clear()
    return 0


if __name__ == "__main__":
    sys.exit(main())
