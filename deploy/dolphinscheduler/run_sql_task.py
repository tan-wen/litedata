#!/usr/bin/env python3
"""Execute a versioned SQL node in a DolphinScheduler DAG. Credentials stay in worker configuration."""
import argparse
import re
import sys
import time
from urllib.error import HTTPError

import pymysql
from run_pipeline import parse_mysql_jdbc, platform_request  # Also loads the root-only worker configuration.
import os


def execute(claim):
    sql = claim.get("sql", "")
    if not isinstance(sql, str) or not re.match(r"^INSERT\s+INTO\s+", sql, re.IGNORECASE) or ";" in sql:
        raise ValueError("invalid claimed SQL")
    target = claim.get("targetTable", "")
    if not re.fullmatch(r"[a-z_][a-z0-9_]{0,63}", target):
        raise ValueError("invalid claimed target")
    host, port, database = parse_mysql_jdbc(os.environ.get("DORIS_WRITER_JDBC_URL", "jdbc:mysql://doris:9030/warehouse"))
    if database != "warehouse":
        raise ValueError("SQL processing is restricted to warehouse")
    connection = pymysql.connect(host=host, port=port, database=database, user=os.environ["DORIS_WRITER_USER"],
                                 password=os.environ["DORIS_WRITER_PASSWORD"], connect_timeout=10, read_timeout=2760,
                                 write_timeout=30, autocommit=True, charset="utf8mb4")
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET query_timeout=2700")
            cursor.execute("SET insert_timeout=2700")
            cursor.execute(sql)
    finally:
        connection.close()


def finish(run_id, success):
    for attempt in range(3):
        try:
            platform_request(f"/internal/sql-tasks/runs/{run_id}/finish", {"success": success})
            return True
        except Exception:
            if attempt < 2:
                time.sleep(2 ** attempt)
    print("SQL completion callback failed; verify engine execution before releasing the platform run lock", file=sys.stderr)
    return False


def claim_task(args):
    # Separate workflows may share an upstream node. Wait for its mutex, then execute this chain's own run.
    deadline = time.monotonic() + 300
    while True:
        try:
            return platform_request(f"/internal/sql-tasks/{args.task_id}/claim",
                                    {"version": args.version, "rootId": args.root_id, "graphHash": args.graph_hash})
        except RuntimeError as error:
            cause = error.__cause__
            if not isinstance(cause, HTTPError) or cause.code != 423 or time.monotonic() >= deadline:
                raise
            time.sleep(5)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--task-id", type=int, required=True)
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--root-id", type=int, required=True)
    parser.add_argument("--graph-hash", required=True)
    args = parser.parse_args(argv)
    run_id = None
    try:
        _, claim = claim_task(args)
        run_id = claim.get("runId")
        if not isinstance(run_id, int) or run_id <= 0:
            raise ValueError("missing run id")
        execute(claim)
        if not finish(run_id, True):
            return 1
        print(f"SQL task {args.task_id} run {run_id} completed")
        return 0
    except Exception as error:
        # Never print SQL, response bodies, DB connection strings, or exception text.
        code = error.args[0] if isinstance(error, pymysql.err.OperationalError) and error.args else None
        uncertain = isinstance(error, (OSError, pymysql.err.InterfaceError)) or code in (2006, 2013, 2055)
        print("SQL task failed; execution outcome uncertain, platform run lock retained" if uncertain
              else "SQL task failed; inspect the node status and SQL definition", file=sys.stderr)
        if run_id is not None and not uncertain:
            finish(run_id, False)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
