import unittest

from dashboard import render_dashboard


class DimensionDashboardTest(unittest.TestCase):
    def test_dashboard_requires_observed_dimension_rows(self):
        page = render_dashboard({"status": "running"}, {"models": [{"layer": "DIM"}]})
        self.assertIn("尚无 DIM 实测结果", page)
        self.assertNotIn("SCD1 属性覆盖验证", page)

    def test_dashboard_shows_observed_members_changes_and_dimension_lineage_safely(self):
        report = {"status": "success", "prefix": "cpdim_test", "platform_url": "http://localhost:8080",
                  "dimension_lineage_path": "/metrics/1/lineage?field=enterprise_id&mode=direct",
                  "dimension": {"modelId": 7, "tableName": "cpdim_test_dim_enterprise", "businessKeys": "enterprise_id",
                                "labelField": "enterprise_name", "strategy": "SCD1",
                                "members": [{"enterprise_id": 1, "enterprise_name": "<img src=x onerror=alert(1)>", "product_code": "RESIN_A"}],
                                "scd1": {"status": "passed", "before": "原名", "after": "新名", "factAfter": "新名", "restored": "原名",
                                         "memberCountBefore": 3, "memberCountAfter": 3}}}
        page = render_dashboard(report, {})
        self.assertIn("SCD1 属性覆盖验证", page)
        self.assertIn("3 → 3", page)
        self.assertIn("DWD 重算后", page)
        self.assertIn("/models/7", page)
        self.assertIn("field=enterprise_id&amp;mode=direct", page)
        self.assertIn("&lt;img src=x onerror=alert(1)&gt;", page)
        self.assertNotIn("<img src=x", page)


if __name__ == "__main__":
    unittest.main()
