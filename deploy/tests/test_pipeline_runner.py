"""Validate DataX reader dialects without contacting a database or exposing credentials."""
import importlib.util
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


def load_runner():
    path = Path(__file__).resolve().parents[1] / "dolphinscheduler" / "run_pipeline.py"
    spec = importlib.util.spec_from_file_location("pipeline_runner_under_test", path)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, {"pymysql": types.ModuleType("pymysql")}):
        spec.loader.exec_module(module)
    return module


class PipelineRunnerTests(unittest.TestCase):
    def setUp(self):
        self.runner = load_runner()
        self.environment = patch.dict(os.environ, {"DORIS_WRITER_USER": "writer", "DORIS_WRITER_PASSWORD": "test-only", "DORIS_WRITER_JDBC_URL": "jdbc:mysql://warehouse:9030/warehouse"})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def claim(self, kind, url, schema=""):
        return {"source": {"databaseType": kind, "jdbcUrl": url, "username": "reader", "password": "test-only", "schema": schema, "columns": ["id", "updated_at"]},
                "sourceTable": "orders", "watermarkColumn": "updated_at", "targetTable": "ods_orders", "lowerBound": "2026-10-01 00:00:00.000001", "upperBound": "2026-10-01 01:00:00.123456"}

    def test_builds_type_specific_reader_queries_with_fixed_bounds(self):
        cases = [("MYSQL", "jdbc:mysql://host/sales", "", "mysqlreader", "FROM `orders`", "'2026-10-01"),
                 ("POSTGRESQL", "jdbc:postgresql://host/sales", "source_data", "postgresqlreader", 'FROM "source_data"."orders"', "TIMESTAMP '2026-10-01"),
                 ("SQLSERVER", "jdbc:sqlserver://host;databaseName=sales", "dbo", "sqlserverreader", "FROM [dbo].[orders]", "CONVERT(datetime2(6), '2026-10-01"),
                 ("ORACLE", "jdbc:oracle:thin:@//host:1521/service", "SALES", "oraclereader", 'FROM "SALES"."orders"', "TO_TIMESTAMP('2026-10-01")]
        for kind, url, schema, reader, table_sql, time_sql in cases:
            with self.subTest(kind=kind):
                content = self.runner.build_datax_job(self.claim(kind, url, schema))["job"]["content"][0]
                self.assertEqual(reader, content["reader"]["name"])
                query = content["reader"]["parameter"]["connection"][0]["querySql"][0]
                self.assertIn(table_sql, query)
                self.assertIn(time_sql, query)
                self.assertIn(".000001", query)
                self.assertIn(".123456", query)
                self.assertIn(" AS ", query)
                self.assertEqual(["id", "updated_at"], content["writer"]["parameter"]["column"])

    def test_rejects_untrusted_schema_columns_and_mismatched_type(self):
        for field, value in [("schema", "dbo;DROP"), ("columns", ["id", "updated_at;DROP"]), ("columns", ["id", "id"]), ("databaseType", "ORACLE")]:
            claim = self.claim("POSTGRESQL", "jdbc:postgresql://host/sales", "public")
            claim["source"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.runner.build_datax_job(claim)

    def test_completion_depends_only_on_datax_and_does_not_execute_legacy_sql(self):
        claim = self.claim("MYSQL", "jdbc:mysql://host/sales")
        claim.update(runId=7, transformSql="INSERT INTO ads_orders SELECT * FROM ods_orders")
        for code in (0, 1):
            with self.subTest(datax_exit=code), \
                 patch.object(sys, "argv", ["run_pipeline.py", "--pipeline-id", "1"]), \
                 patch.object(self.runner, "platform_request", return_value=(200, claim)), \
                 patch.object(self.runner, "execute_datax", return_value=code), \
                 patch.object(self.runner, "finish", return_value=True) as finish, \
                 patch.object(self.runner.pymysql, "connect", create=True) as connect, \
                 patch.object(self.runner, "redact_datax_tail", return_value="DataX failed"):
                self.assertEqual(0 if code == 0 else 1, self.runner.main())
                self.assertEqual((7, code == 0), finish.call_args.args[:2])
                connect.assert_not_called()

    def test_auto_ods_names_follow_source_record_order_even_when_doris_keys_are_reordered(self):
        claim=self.claim("MYSQL", "jdbc:mysql://host/sales")
        claim["source"]["columns"]=["CustomerName","OrderID","updated_at"]
        claim["targetColumns"]=["customername","orderid","updated_at"]
        content=self.runner.build_datax_job(claim)["job"]["content"][0]
        self.assertEqual(["customername","orderid","updated_at"],content["writer"]["parameter"]["column"])
        self.assertEqual(["`CustomerName`","`OrderID`","`updated_at`"],content["reader"]["parameter"]["column"])
        for invalid in (["orderid"],["id","id","updated_at"],["id;drop","orderid","updated_at"]):
            with self.subTest(target_columns=invalid), self.assertRaises(ValueError):
                self.runner.build_datax_job(dict(claim,targetColumns=invalid))


if __name__ == "__main__":
    unittest.main()
