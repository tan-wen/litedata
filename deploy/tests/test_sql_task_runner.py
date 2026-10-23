"""Unit tests for run_sql_task without a worker, Doris, or third-party packages."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import MagicMock, Mock, patch
from urllib.error import HTTPError


RUNNER = Path(__file__).parents[1] / "dolphinscheduler" / "run_sql_task.py"
SECRET_SQL = "INSERT INTO orders SELECT 'sql-secret-password'"
SECRET_RESPONSE = "response-token-secret"
PASSWORD = "doris-password-secret"


def load_runner():
    """Load a fresh runner with only its two external imports stubbed."""
    class OperationalError(Exception):
        pass

    class InterfaceError(Exception):
        pass

    pymysql = types.ModuleType("pymysql")
    pymysql.err = types.SimpleNamespace(OperationalError=OperationalError, InterfaceError=InterfaceError)
    pymysql.connect = Mock()
    pipeline = types.ModuleType("run_pipeline")
    pipeline.parse_mysql_jdbc = Mock(return_value=("doris", 9030, "warehouse"))
    pipeline.platform_request = Mock()
    name = "run_sql_task_test_subject"
    spec = importlib.util.spec_from_file_location(name, RUNNER)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, {"pymysql": pymysql, "run_pipeline": pipeline, name: module}):
        spec.loader.exec_module(module)
    return module, pymysql


class SqlTaskRunnerTest(unittest.TestCase):
    def setUp(self):
        self.runner, self.pymysql = load_runner()
        self.environment = patch.dict(os.environ, {
            "DORIS_WRITER_USER": "writer", "DORIS_WRITER_PASSWORD": PASSWORD,
            "DORIS_WRITER_JDBC_URL": "jdbc:mysql://doris:9030/warehouse",
        }, clear=False)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    @staticmethod
    def arguments():
        return ["--task-id", "8", "--version", "3", "--root-id", "1", "--graph-hash", "hash"]

    def successful_connection(self):
        connection = MagicMock()
        cursor = Mock()
        connection.cursor.return_value.__enter__.return_value = cursor
        self.pymysql.connect.return_value = connection
        return connection, cursor

    def test_success_claims_executes_and_finishes(self):
        connection, cursor = self.successful_connection()
        claim = {"runId": 17, "sql": SECRET_SQL, "targetTable": "orders"}
        self.runner.platform_request.side_effect = [(None, claim), (None, {})]

        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = self.runner.main(self.arguments())

        self.assertEqual(0, result)
        self.assertEqual("/internal/sql-tasks/8/claim", self.runner.platform_request.call_args_list[0].args[0])
        self.assertEqual(("/internal/sql-tasks/runs/17/finish", {"success": True}), self.runner.platform_request.call_args_list[1].args)
        self.assertEqual(["SET query_timeout=2700", "SET insert_timeout=2700", SECRET_SQL], [call.args[0] for call in cursor.execute.call_args_list])
        connection.close.assert_called_once_with()
        self.assertNotIn(SECRET_SQL, output.getvalue())

    def test_illegal_claim_sql_is_not_executed_or_leaked(self):
        illegal = "DROP TABLE orders; " + SECRET_RESPONSE
        self.runner.platform_request.side_effect = [(None, {"runId": 17, "sql": illegal, "targetTable": "orders"}), (None, {})]

        with contextlib.redirect_stderr(io.StringIO()) as error:
            result = self.runner.main(self.arguments())

        self.assertEqual(1, result)
        self.pymysql.connect.assert_not_called()
        self.assertEqual(("/internal/sql-tasks/runs/17/finish", {"success": False}), self.runner.platform_request.call_args_list[1].args)
        self.assert_safe(error.getvalue(), illegal)

    def test_finish_retries_callback(self):
        self.runner.platform_request.side_effect = [RuntimeError(SECRET_RESPONSE), RuntimeError("again"), (None, {})]
        with patch.object(self.runner.time, "sleep") as sleep, contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertTrue(self.runner.finish(17, False))
        self.assertEqual(3, self.runner.platform_request.call_count)
        self.assertEqual([1, 2], [call.args[0] for call in sleep.call_args_list])
        self.assert_safe(error.getvalue())

    def test_claim_retries_busy_423_then_claims_own_run(self):
        busy = RuntimeError("busy")
        busy.__cause__ = HTTPError("http://platform/internal", 423, SECRET_RESPONSE, None, None)
        expected = (None, {"runId": 29, "sql": SECRET_SQL, "targetTable": "orders"})
        self.runner.platform_request.side_effect = [busy, expected]
        args = types.SimpleNamespace(task_id=8, version=3, root_id=1, graph_hash="new-hash")

        with patch.object(self.runner.time, "monotonic", side_effect=[100, 101]), patch.object(self.runner.time, "sleep") as sleep:
            self.assertEqual(expected, self.runner.claim_task(args))

        self.assertEqual(2, self.runner.platform_request.call_count)
        self.assertEqual(5, sleep.call_args.args[0])
        self.assertEqual("new-hash", self.runner.platform_request.call_args.args[1]["graphHash"])

    def test_old_hash_409_exits_without_waiting_or_reusing_claim(self):
        old_hash = RuntimeError("conflict")
        old_hash.__cause__ = HTTPError("http://platform/internal", 409, SECRET_RESPONSE, None, None)
        self.runner.platform_request.side_effect = old_hash

        with patch.object(self.runner.time, "sleep") as sleep, contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(1, self.runner.main(self.arguments()))

        self.assertEqual(1, self.runner.platform_request.call_count)
        sleep.assert_not_called()
        self.pymysql.connect.assert_not_called()
        self.assert_safe(error.getvalue())

    def test_network_uncertainty_for_2006_and_2013_keeps_lock(self):
        for code in (2006, 2013):
            with self.subTest(code=code):
                runner, pymysql = load_runner()
                connection = MagicMock()
                cursor = Mock()
                connection.cursor.return_value.__enter__.return_value = cursor
                cursor.execute.side_effect = [None, None, pymysql.err.OperationalError(code, PASSWORD)]
                pymysql.connect.return_value = connection
                runner.platform_request.return_value = (None, {"runId": 17, "sql": SECRET_SQL, "targetTable": "orders"})
                with contextlib.redirect_stderr(io.StringIO()) as error:
                    self.assertEqual(1, runner.main(self.arguments()))
                self.assertEqual(1, runner.platform_request.call_count)
                self.assertIn("outcome uncertain", error.getvalue())
                self.assert_safe(error.getvalue())

    def test_regular_sql_failure_finishes_false_without_leaking_details(self):
        connection = MagicMock()
        cursor = Mock()
        connection.cursor.return_value.__enter__.return_value = cursor
        cursor.execute.side_effect = [None, None, RuntimeError(SECRET_RESPONSE)]
        self.pymysql.connect.return_value = connection
        self.runner.platform_request.side_effect = [(None, {"runId": 17, "sql": SECRET_SQL, "targetTable": "orders"}), (None, {})]

        with contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(1, self.runner.main(self.arguments()))

        self.assertEqual(("/internal/sql-tasks/runs/17/finish", {"success": False}), self.runner.platform_request.call_args_list[1].args)
        self.assert_safe(error.getvalue())

    def assert_safe(self, message, extra=""):
        for secret in (SECRET_SQL, SECRET_RESPONSE, PASSWORD, extra):
            if secret:
                self.assertNotIn(secret, message)


if __name__ == "__main__":
    unittest.main()
