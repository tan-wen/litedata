import copy
import datetime as dt
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, call, patch

from run_demo import Client, Demo, DemoFailure, identifier, workflow_node
from scenario import build_scenario


class RunnerContractTest(unittest.TestCase):
    def setUp(self):
        self.scenario = build_scenario("cpdim_resin01", dt.date(2026, 10, 24))

    def test_form_post_keeps_csrf_version_and_repeated_dependencies(self):
        client = Client("http://localhost:8080/")
        client.request = Mock(return_value=(200, "saved"))
        page = '<form action="/transforms" method="post"><input name="_csrf" value="test-csrf"><input name="version" value="4"></form>'
        client.post(page, "/transforms", {"upstreamPipelineIds": [1, 2, 3]})
        client.request.assert_called_once_with("POST", "/transforms", {"_csrf": "test-csrf", "version": "4", "upstreamPipelineIds": [1, 2, 3]})

    def test_workflow_node_requires_the_single_expand_response_node(self):
        node = {"key": "sql-7", "name": "ads"}
        self.assertEqual(node, workflow_node({"nodes": [node]}, "sql-7"))
        with self.assertRaisesRegex(DemoFailure, "工作流无法加载节点"):
            workflow_node({"node": node}, "sql-7")
        with self.assertRaisesRegex(DemoFailure, "工作流无法加载节点"):
            workflow_node({"nodes": [node, node]}, "sql-7")

    def test_identifier_reads_transform_id_from_list_edit_link(self):
        page = "<table><tr><td>cpdim_demo ADS</td><td><a href=\"/transforms/42/edit\">编辑</a></td></tr></table>"
        self.assertEqual(42, identifier(page, "cpdim_demo ADS", "transforms", "edit"))

    def test_all_pipelines_must_report_success_before_sql_workflow(self):
        demo = Demo.__new__(Demo)
        demo.args = SimpleNamespace(timeout=1)
        demo.manifest = {"source_tables": [{"key": "enterprise", "tableName": "src_enterprise"}, {"key": "production", "tableName": "src_production"}, {"key": "utility", "tableName": "src_utility"}]}
        demo.report = {"resources": {"pipelines": {"enterprise": 1, "production": 2, "utility": 3}}}
        demo.client = Mock()
        demo.client.request.side_effect = [
            (200, "<table><tr><td><a href='/scheduling/pipeline/1/instances/10'>old</a></td></tr></table>"),
            (200, "<table><tr><td><a href='/scheduling/pipeline/2/instances/20'>old</a></td></tr></table>"),
            (200, "<form action='/pipelines/1/run'></form>"),
            (200, "<form action='/pipelines/2/run'></form>"),
            (200, "<table><tr><td><a href='/scheduling/pipeline/1/instances/11'>run</a></td></tr></table>"),
            (200, "<table><tr><td><a href='/scheduling/pipeline/2/instances/21'>run</a></td></tr></table>"),
            (200, "<table><tr><td><a href='/scheduling/pipeline/3/instances/30'>old</a></td></tr></table>"),
            (200, "<form action='/pipelines/3/run'></form>"),
            (200, "<table><tr><td><a href='/scheduling/pipeline/3/instances/31'>run</a></td></tr></table>"),
        ]
        demo.client.post.return_value = (200, "已接受执行请求")
        demo.client.request_json.side_effect = [(200, {"callbackPage": {"items": [{"status": "SUCCESS"}]}}),
                                                 (200, {"callbackPage": {"items": [{"status": "SUCCESS"}]}}),
                                                 (200, {"callbackPage": {"items": [{"id": 99, "qualityRunId": 199, "status": "SUCCESS", "qualityStatus": "PASSED"}]}})]
        demo.record = Mock()

        with patch("run_demo.time.sleep"), patch("run_demo.source_sql") as source:
            evidence = demo.run_pipelines(1)

        self.assertEqual([call("GET", "/scheduling/pipeline/1"), call("GET", "/scheduling/pipeline/2"),
                          call("GET", "/pipelines"), call("GET", "/pipelines"),
                          call("GET", "/scheduling/pipeline/1"), call("GET", "/scheduling/pipeline/2"),
                          call("GET", "/scheduling/pipeline/3"), call("GET", "/pipelines"), call("GET", "/scheduling/pipeline/3")],
                         demo.client.request.call_args_list)
        self.assertEqual([call("GET", "/scheduling/pipeline/1/instances/11/status"), call("GET", "/scheduling/pipeline/2/instances/21/status"),
                          call("GET", "/scheduling/pipeline/3/instances/31/status")],
                         demo.client.request_json.call_args_list)
        self.assertEqual({"pipelineRunId": 99, "qualityRunId": 199, "qualityStatus": "PASSED"}, evidence)
        self.assertEqual(3, source.call_count)
        demo.record.assert_called_once_with("ODS 独立同步就绪 1", enterpriseProduction=["enterprise", "production"], utility=evidence)

    def test_new_pipeline_failure_blocks_sql_workflow(self):
        demo = Demo.__new__(Demo)
        demo.args = SimpleNamespace(timeout=1)
        demo.manifest = {"source_tables": [{"key": "enterprise", "tableName": "src_enterprise"}, {"key": "production", "tableName": "src_production"}, {"key": "utility", "tableName": "src_utility"}]}
        demo.report = {"resources": {"pipelines": {"enterprise": 1, "production": 2, "utility": 3}}}
        demo.client = Mock()
        demo.client.request.side_effect = [(200, "<table></table>"), (200, "<table></table>"),
                                            (200, "<form action='/pipelines/1/run'></form>"), (200, "<form action='/pipelines/2/run'></form>"),
                                            (200, "<table><tr><td><a href='/scheduling/pipeline/1/instances/11'>run</a></td></tr></table>"),
                                            (200, "<table><tr><td><a href='/scheduling/pipeline/2/instances/21'>run</a></td></tr></table>"),
                                            (200, "<table></table>"), (200, "<form action='/pipelines/3/run'></form>"),
                                            (200, "<table><tr><td><a href='/scheduling/pipeline/3/instances/31'>run</a></td></tr></table>")]
        demo.client.post.return_value = (200, "已接受执行请求")
        demo.client.request_json.side_effect = [(200, {"callbackPage": {"items": [{"status": "SUCCESS"}]}}),
                                                 (200, {"callbackPage": {"items": [{"status": "SUCCESS"}]}}),
                                                 (200, {"callbackPage": {"items": [{"status": "FAILED"}]}})]

        with patch("run_demo.source_sql"), self.assertRaisesRegex(DemoFailure, "utility ODS 同步失败"):
            demo.run_pipelines(1)

    def test_model_posts_layer_relative_suffix_and_rejects_duplicate_prefix(self):
        demo = Demo.__new__(Demo)
        demo.args = SimpleNamespace(prefix="cpdim_resin01")
        demo.client = Client("http://localhost:8080/")
        demo.report = {"resources": {"models": {}}}
        model = next(item for item in self.scenario["models"] if item["key"] == "dwd_utility_day")
        form = ('<form action="/models"><input name="_csrf" value="token"><select name="subjectArea"><option value="chemical_park">化工园区</option></select></form>'
                '<form action="/models/42/physicalize"><input name="_csrf" value="token"></form>')
        calls = []

        def request(method, path, fields=None, headers=None):
            calls.append((method, path, fields))
            if method == "GET":
                return 200, form
            if path == "/models":
                demo.client.last_path = "/models/42"
                return 200, "逻辑模型已保存" + form
            return 200, "Doris 表已创建"

        demo.client.request = request
        demo.model(model)
        save_fields = next(fields for method, path, fields in calls if method == "POST" and path == "/models")
        self.assertEqual("dwd_cpdim_resin01_utility_day", save_fields["tableName"])
        self.assertEqual("cpdim_resin01_utility_day", save_fields["tableNameSuffix"])
        duplicate = dict(model, tableName="dwd_dwd_cpdim_resin01_utility_day")
        with self.assertRaisesRegex(DemoFailure, "重复或缺少"):
            demo.model(duplicate)

    def test_datetime_check_detects_real_eight_hour_shift(self):
        demo = Demo.__new__(Demo)
        demo.manifest = self.scenario
        rows = [{key: r[key] for key in ("record_id", "stat_date", "period_end")} for r in self.scenario["source_tables"][1]["rows"]]
        with patch("run_demo.warehouse", return_value=rows):
            demo.check_datetime_parity()
        shifted = copy.deepcopy(rows)
        shifted[0]["period_end"] = shifted[0]["period_end"].replace("00:00:00", "08:00:00")
        with patch("run_demo.warehouse", return_value=shifted):
            with self.assertRaisesRegex(DemoFailure, "DATETIME"):
                demo.check_datetime_parity()

    def test_scd1_evidence_contains_observed_dimension_and_fact_names(self):
        demo = Demo.__new__(Demo)
        demo.manifest = self.scenario
        demo.report = {"resources": {"models": {"dim_enterprise": 7}}}
        demo.run_chain = Mock()
        demo.counts = Mock(return_value={"dim_enterprise": 3})
        demo.record = Mock()
        members = [{key: row[key] for key in ("enterprise_id", "enterprise_name", "product_code")}
                   for row in self.scenario["source_tables"][0]["rows"]]
        updated = [{"enterprise_name": members[0]["enterprise_name"] + "（维度更新）"}]
        with patch("run_demo.warehouse", side_effect=[members, updated, updated, members]), patch("run_demo.source_sql") as source:
            demo.check_dimension_update()
        self.assertEqual(2, source.call_count)
        self.assertEqual([(4,), (5,)], [call.args for call in demo.run_chain.call_args_list])
        observed = demo.report["dimension"]
        self.assertEqual(members, observed["members"])
        self.assertEqual("SCD1", observed["strategy"])
        self.assertEqual(updated[0]["enterprise_name"], observed["scd1"]["factAfter"])
        self.assertEqual(members[0]["enterprise_name"], observed["scd1"]["restored"])

    def test_scd1_rejects_fact_names_that_have_not_been_refreshed(self):
        demo = Demo.__new__(Demo)
        demo.manifest = self.scenario
        demo.run_chain = Mock()
        demo.counts = Mock(return_value={"dim_enterprise": 3})
        members = self.scenario["source_tables"][0]["rows"]
        updated = [{"enterprise_name": members[0]["enterprise_name"] + "（维度更新）"}]
        stale = [{"enterprise_name": members[0]["enterprise_name"]}]
        with patch("run_demo.warehouse", side_effect=[members, updated, stale]), patch("run_demo.source_sql"):
            with self.assertRaisesRegex(DemoFailure, "事实宽表重算"):
                demo.check_dimension_update()

    def test_result_validation_catches_wrong_rolling_window(self):
        demo = Demo.__new__(Demo)
        demo.manifest = self.scenario
        demo.report = {"results": {}}
        expected = self.scenario["expected"]
        daily = []
        for row in expected["dwd_final_rows"]:
            actual = dict(row)
            actual["stat_date"] = actual["stat_date"] + "T00:00:00Z"
            actual["period_end"] = actual["period_end"].replace(" ", "T")
            daily.append(actual)
        park = dict(expected["park7d_row"])
        park["as_of_date"] = park["as_of_date"] + "T00:00:00Z"
        zero = next(row for row in daily if row["enterprise_id"] == 3 and row["production_tons"] == "0.000")
        trace = dict(zero, steam_per_ton=None, kwh_per_ton=None)
        responses = {
            "park_7d": [park],
            "enterprise_7d": expected["enterprise7d_rows"],
            "daily_all": daily,
            "daily_trace": [trace],
            "metric_steam_7d_service": [{"enterprise_id": r["enterprise_id"], "metric_value": r["steam_per_ton"]} for r in expected["enterprise7d_rows"]],
        }
        demo.query = lambda key, params=None, authenticated=True: responses[key] if authenticated else []
        demo.check_results()
        responses["metric_steam_7d_service"][-1]["metric_value"] = "1.200000"
        with self.assertRaisesRegex(DemoFailure, "加权口径"):
            demo.check_results()
        responses["metric_steam_7d_service"] = []
        with self.assertRaisesRegex(DemoFailure, "完整企业集合"):
            demo.check_results()

    def test_result_validation_catches_duplicate_or_wrong_daily_rows(self):
        demo = Demo.__new__(Demo)
        demo.manifest = self.scenario
        demo.report = {"results": {}}
        expected = self.scenario["expected"]
        daily = [dict(row, stat_date=row["stat_date"] + "T00:00:00Z", period_end=row["period_end"].replace(" ", "T")) for row in expected["dwd_final_rows"]]
        park = dict(expected["park7d_row"], as_of_date=expected["park7d_row"]["as_of_date"] + "T00:00:00Z")
        zero = next(row for row in daily if row["enterprise_id"] == 3 and row["production_tons"] == "0.000")
        responses = {"park_7d": [park], "enterprise_7d": expected["enterprise7d_rows"], "daily_all": daily,
                     "daily_trace": [dict(zero, steam_per_ton=None, kwh_per_ton=None)],
                     "metric_steam_7d_service": [{"enterprise_id": row["enterprise_id"], "metric_value": row["steam_per_ton"]} for row in expected["enterprise7d_rows"]]}
        demo.query = lambda key, params=None, authenticated=True: responses[key] if authenticated else []
        duplicate = dict(daily[-1], record_id=daily[0]["record_id"])
        responses["daily_all"] = daily[:-1] + [duplicate]
        with self.assertRaisesRegex(DemoFailure, "重复 record_id"):
            demo.check_results()
        responses["daily_all"] = daily
        responses["daily_all"][0]["steam_tons"] = "999.000"
        with self.assertRaisesRegex(DemoFailure, "日明细度量"):
            demo.check_results()


if __name__ == "__main__":
    unittest.main()
