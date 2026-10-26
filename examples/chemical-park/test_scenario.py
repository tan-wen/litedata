import datetime as dt
import re
import unittest
from decimal import Decimal, ROUND_HALF_UP

from scenario import build_scenario


class ScenarioTest(unittest.TestCase):
    def setUp(self):
        self.as_of = dt.date(2026, 10, 24)
        self.scenario = build_scenario("cp_resin01", self.as_of)

    def test_counts_and_filtered_weighted_expectations_are_independent(self):
        sources = {source["key"]: source["rows"] for source in self.scenario["source_tables"]}
        production = {(r["enterprise_id"], r["stat_date"]): Decimal(r["production_tons"])
                      for r in sources["production"]}
        dwd = [r for r in sources["utility"] if r["is_valid"] == 1
               and all(Decimal(r[c]) >= 0 for c in ("steam_tons", "electricity_kwh", "water_m3"))
               and production[(r["enterprise_id"], r["stat_date"])] >= 0]
        seven = [r for r in dwd if self.as_of - dt.timedelta(days=7) <= dt.date.fromisoformat(r["stat_date"]) < self.as_of]
        totals = {column: sum((Decimal(r[column]) for r in seven), Decimal())
                  for column in ("steam_tons", "electricity_kwh", "water_m3")}
        totals["production_tons"] = sum((production[(r["enterprise_id"], r["stat_date"])] for r in seven), Decimal())
        expected = self.scenario["expected"]
        self.assertEqual(len(dwd), expected["dwd_initial_count"])
        self.assertEqual(expected["dwd_final_count"], len(dwd) + 1)
        self.assertEqual({key: str(value.quantize(Decimal(".001"))) for key, value in totals.items()}, expected["park7d_totals"])
        ratio = (totals["steam_tons"] / totals["production_tons"]).quantize(Decimal(".000001"), rounding=ROUND_HALF_UP)
        self.assertEqual(f"{ratio:f}", expected["park7d_steam_per_ton"])

    def test_zero_production_and_seven_day_boundary(self):
        zero = self.scenario["expected"]["zero_production_row"]
        self.assertEqual(zero["stat_date"], "2026-10-23")
        self.assertEqual(zero["production_tons"], "0.000")
        self.assertIsNone(zero["steam_per_ton"])
        self.assertIn("DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY)", self.scenario["transforms"][1]["sqlText"])

    def test_corrected_daily_projection_contains_every_business_field(self):
        expected = self.scenario["expected"]
        rows = expected["dwd_final_rows"]
        self.assertEqual(24, len(rows))
        self.assertEqual(list(range(1, 25)), [row["record_id"] for row in rows])
        corrected = next(row for row in rows if row["enterprise_id"] == 2 and row["stat_date"] == "2026-10-16")
        self.assertEqual("176.000", corrected["steam_tons"])
        self.assertEqual({"record_id", "enterprise_id", "enterprise_name", "product_code", "stat_date", "period_end", "steam_tons", "electricity_kwh", "water_m3", "production_tons"}, set(corrected))
        park = expected["park7d_row"]
        self.assertEqual({"park_id": 1, "as_of_date": "2026-10-24", "enterprise_count": 3, "valid_days": 21}, {key: park[key] for key in ("park_id", "as_of_date", "enterprise_count", "valid_days")})

    def test_references_api_fields_formula_codes_and_slug_pattern(self):
        models = {model["key"]: model for model in self.scenario["models"]}
        self.assertEqual({"ods_enterprise", "ods_utility", "ods_production"}, {key for key in models if key.startswith("ods_")})
        table_names = {model["tableName"] for model in models.values()}
        for model in models.values():
            self.assertTrue(all(set(field) == {"name", "dataType", "keyColumn", "nullable", "comment", "standard"} for field in model["fields"]))
        transform_inputs = {
            "dwd_utility_day": {"ods_enterprise", "ods_utility", "ods_production"},
            "dws_enterprise_7d": {"dwd_utility_day"},
            "ads_park_7d": {"dws_enterprise_7d"},
        }
        for transform in self.scenario["transforms"]:
            self.assertIn(models[transform["targetModel"]]["tableName"], transform["sqlText"])
            for key in transform_inputs[transform["key"]]:
                self.assertIn(models[key]["tableName"], transform["sqlText"])
        for pipeline in self.scenario["pipelines"]:
            self.assertIn(pipeline["targetModel"], models)
        for service in self.scenario["services"]:
            self.assertRegex(service["slug"], r"^cp-resin01-[a-z0-9-]+$")
            if "querySql" in service:
                self.assertTrue(any(table in service["querySql"] for table in table_names))
        codes = {metric["code"] for metric in self.scenario["metrics"]}
        metric_keys = {metric["key"] for metric in self.scenario["metrics"]}
        for metric in self.scenario["metrics"]:
            for code in re.findall(r"\[([^]]+)]", metric["formula"]):
                self.assertIn(code, codes)
            if "baseMetricKey" in metric:
                self.assertIn(metric["baseMetricKey"], metric_keys)
        for quality in self.scenario["qualities"]:
            for rule in quality["rules"]:
                self.assertEqual(set(rule), {"name", "type", "columns", "parameter1", "parameter2", "referenceTable", "referenceColumn", "toleranceCount", "tolerancePercent", "severity"})
                if rule["type"] == "NON_NEGATIVE":
                    self.assertEqual(len(rule["columns"].split(",")), 1)

    def test_determinism_and_identifier_guard(self):
        self.assertEqual(self.scenario, build_scenario("cp_resin01", self.as_of))
        with self.assertRaises(ValueError):
            build_scenario("bad-prefix", self.as_of)

    def test_original_example_keeps_direct_ods_join_without_dim(self):
        self.assertEqual(6, len(self.scenario["models"]))
        self.assertEqual(3, len(self.scenario["transforms"]))
        self.assertFalse(any(model["layer"] == "DIM" for model in self.scenario["models"]))
        dwd = next(task for task in self.scenario["transforms"] if task["key"] == "dwd_utility_day")
        self.assertIn("cp_resin01_ods_enterprise", dwd["sqlText"])
        self.assertIn("enterprise", dwd["upstreamPipelines"])


if __name__ == "__main__":
    unittest.main()
