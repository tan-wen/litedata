#!/usr/bin/env python3
"""Run one claimed incremental pipeline inside a DolphinScheduler worker.

The platform owns pipeline state. This process only uses the claim's immutable
[lowerBound, upperBound) range and can therefore be safely retried: the Doris
UNIQUE KEY table upserts duplicate rows, and the platform advances the watermark
only after this process reports success.
"""
import argparse
import json
import os
import re
import subprocess
import signal
import sys
import tempfile
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import pymysql

# sudo -i in a DS tenant intentionally clears environment variables.
_worker_config = Path("/run/litedata-worker.json")
if _worker_config.is_file():
    for _key, _value in json.loads(_worker_config.read_text()).items():
        os.environ.setdefault(_key, _value)

IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{6}$")
TIMEOUT_SECONDS = 30


def require_identifier(value, name):
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise ValueError(f"{name} must be one SQL identifier")
    return value


def platform_request(path, payload=None):
    base = os.environ.get("PLATFORM_URL", "http://platform:8080").rstrip("/")
    token = os.environ["PLATFORM_INTERNAL_TOKEN"]
    headers = {"X-Internal-Token": token}
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(base + path, data=data, headers=headers, method="POST")
    try:
        with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"platform returned HTTP {error.code}: {body[:400]}") from error
    except URLError as error:
        raise RuntimeError(f"platform request failed: {error.reason}") from error


def parse_mysql_jdbc(jdbc_url):
    match = re.fullmatch(r"jdbc:mysql://([^/:?]+)(?::(\d+))?/([^?]+)(?:\?.*)?", jdbc_url)
    if not match:
        raise ValueError("only jdbc:mysql://host[:port]/database URLs are supported")
    return match.group(1), int(match.group(2) or 3306), require_identifier(match.group(3), "database")


def source_columns(source, table):
    host, port, database = parse_mysql_jdbc(source["jdbcUrl"])
    connection = pymysql.connect(
        host=host, port=port, user=source["username"], password=source["password"],
        database=database, connect_timeout=TIMEOUT_SECONDS, read_timeout=TIMEOUT_SECONDS,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"DESCRIBE `{table}`")
            columns = [require_identifier(row[0], "source column") for row in cursor.fetchall()]
    finally:
        connection.close()
    if not columns:
        raise RuntimeError("source table has no columns")
    return columns


def build_datax_job(claim):
    source = claim["source"]
    table = require_identifier(claim["sourceTable"], "sourceTable")
    watermark = require_identifier(claim["watermarkColumn"], "watermarkColumn")
    target = require_identifier(claim["targetTable"], "targetTable")
    lower = claim["lowerBound"]
    upper = claim["upperBound"]
    if not isinstance(lower, str) or not isinstance(upper, str) or not TIMESTAMP.fullmatch(lower) or not TIMESTAMP.fullmatch(upper):
        raise ValueError("claim bounds must be microsecond timestamps")
    # The platform inspects JDBC metadata while claiming the immutable range.
    # Keep the MySQL fallback for an older platform during a rolling upgrade.
    kind = source.get("databaseType", "MYSQL")
    readers = {"MYSQL": "mysqlreader", "POSTGRESQL": "postgresqlreader", "SQLSERVER": "sqlserverreader", "ORACLE": "oraclereader"}
    prefixes = {"MYSQL": "jdbc:mysql://", "POSTGRESQL": "jdbc:postgresql://", "SQLSERVER": "jdbc:sqlserver://", "ORACLE": "jdbc:oracle:thin:@//"}
    if kind not in readers or not source["jdbcUrl"].startswith(prefixes[kind]):
        raise ValueError("unsupported or mismatched source database type")
    columns = source.get("columns")
    if columns is None and kind == "MYSQL":
        columns = source_columns(source, table)
    if not isinstance(columns, list) or not columns or len(columns) > 500:
        raise ValueError("claim must include the ordered source columns")
    columns = [require_identifier(column, "source column") for column in columns]
    if len(set(columns)) != len(columns):
        raise ValueError("source columns must be unique")
    if watermark not in columns:
        raise ValueError("watermarkColumn is not present in the source table")
    writer_user = os.environ["DORIS_WRITER_USER"]
    writer_password = os.environ["DORIS_WRITER_PASSWORD"]
    writer_jdbc = os.environ.get("DORIS_WRITER_JDBC_URL", "jdbc:mysql://doris:9030/warehouse")
    doris_host, _, _ = parse_mysql_jdbc(writer_jdbc)
    # All-in-one FE redirects to its internal loopback BE; Compose supplies the
    # worker-reachable BE endpoint. External clusters may keep the FE default.
    load_url = os.environ.get("DORIS_LOAD_URL", f"{doris_host}:8030")
    def quote(value):
        value = require_identifier(value, "SQL identifier")
        if kind == "MYSQL":
            return f"`{value}`"
        if kind == "SQLSERVER":
            return f"[{value}]"
        return f'"{value}"'

    def stamp(value):
        if kind == "ORACLE":
            return f"TO_TIMESTAMP('{value}', 'YYYY-MM-DD HH24:MI:SS.FF6')"
        if kind == "SQLSERVER":
            return f"CONVERT(datetime2(6), '{value}', 121)"
        if kind == "POSTGRESQL":
            return f"TIMESTAMP '{value}'"
        return f"'{value}'"

    schema = source.get("schema", "")
    qualified = (quote(schema) + "." if schema else "") + quote(table)
    quoted_columns = [quote(column) for column in columns]
    timestamp_columns = source.get("timestampColumns", [watermark])
    if not isinstance(timestamp_columns, list) or any(column not in columns for column in timestamp_columns):
        raise ValueError("timestampColumns must be a subset of source columns")

    def projection(column):
        value = quote(column)
        if column not in timestamp_columns:
            return value
        # DataX DateColumn stringification loses fractional seconds. Read local
        # timestamps as text, which Doris casts back to its target DATETIME type.
        if kind == "MYSQL":
            expression = f"DATE_FORMAT({value}, '%Y-%m-%d %H:%i:%s.%f')"
        elif kind == "POSTGRESQL":
            expression = f"TO_CHAR({value}, 'YYYY-MM-DD HH24:MI:SS.US')"
        elif kind == "SQLSERVER":
            expression = f"CONVERT(varchar(27), {value}, 121)"
        else:
            expression = f"TO_CHAR({value}, 'YYYY-MM-DD HH24:MI:SS.FF6')"
        return f"{expression} AS {value}"

    query = (
        f"SELECT {', '.join(projection(column) for column in columns)} FROM {qualified} "
        f"WHERE {quote(watermark)} >= {stamp(lower)} AND {quote(watermark)} < {stamp(upper)}"
    )
    return {
        "job": {
            "setting": {"speed": {"channel": int(os.environ.get("DATAX_CHANNELS", "4"))}, "errorLimit": {"record": 0, "percentage": 0.0}},
            "content": [{
                "reader": {"name": readers[kind], "parameter": {"username": source["username"], "password": source["password"], "column": quoted_columns, "connection": [{"jdbcUrl": [source["jdbcUrl"]], "querySql": [query]}]}},
                "writer": {"name": "doriswriter", "parameter": {"username": writer_user, "password": writer_password, "column": columns, "loadUrl": [load_url], "connection": [{"jdbcUrl": writer_jdbc, "selectedDatabase": "warehouse", "table": [target]}], "loadProps": {"format": "json", "strip_outer_array": "true", "strict_mode": "true"}, "maxRetries": 3}},
            }],
        }
    }


def run_transform(claim):
    statement = claim.get("transformSql")
    if not statement:
        return
    normalized = statement.strip()
    if not re.match(r"^INSERT\s+INTO\s+", normalized, re.IGNORECASE) or ";" in normalized.rstrip(";"):
        raise ValueError("transformSql must be one INSERT INTO ... SELECT statement")
    jdbc_url = os.environ.get("DORIS_WRITER_JDBC_URL", "jdbc:mysql://doris:9030/warehouse")
    host, port, database = parse_mysql_jdbc(jdbc_url)
    connection = pymysql.connect(host=host, port=port, user=os.environ["DORIS_WRITER_USER"], password=os.environ["DORIS_WRITER_PASSWORD"], database=database, connect_timeout=TIMEOUT_SECONDS, read_timeout=TIMEOUT_SECONDS)
    try:
        with connection.cursor() as cursor:
            cursor.execute(normalized)
        connection.commit()
    finally:
        connection.close()


def finish(run_id, success, message):
    for attempt in range(3):
        try:
            platform_request(f"/internal/runs/{run_id}/finish", {"success": success, "message": message[:1000]})
            return True
        except Exception as error:
            if attempt == 2:
                print(f"unable to record run completion after retries: {error}", file=sys.stderr)
                return False
            time.sleep(2 ** attempt)


def redact_datax_tail(path, claim):
    try:
        with path.open("rb") as handle:
            handle.seek(0, 2)
            handle.seek(max(0, handle.tell() - 65536))
            tail = handle.read().decode("utf-8", errors="replace")
    except OSError:
        return "DataX did not produce a readable log"
    secrets = [os.environ.get("PLATFORM_INTERNAL_TOKEN", ""), os.environ.get("DORIS_WRITER_PASSWORD", ""), claim.get("source", {}).get("password", "")]
    for secret in secrets:
        if secret:
            tail = tail.replace(secret, "[REDACTED]")
    return tail[-4000:]


def execute_datax(command, output, environment):
    # Terminate the Java child as well as DataX's Python launcher on timeout.
    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT,
                               env=environment, start_new_session=True)
    try:
        return process.wait(timeout=45 * 60)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pipeline-id", required=True, type=int)
    args = parser.parse_args()
    run_id = None
    temp_path = None
    datax_log_path = None
    try:
        _, claim = platform_request(f"/internal/pipelines/{args.pipeline_id}/claim")
        if not claim.get("runId"):
            raise RuntimeError("claim response did not include runId")
        run_id = claim["runId"]
        job = build_datax_job(claim)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".json", prefix="datax-", delete=False) as handle:
            temp_path = Path(handle.name)
            temp_path.chmod(0o600)
            json.dump(job, handle)
        # DataX prints its full job configuration, including JDBC passwords. Keep that
        # output in a mode-0600 temporary file and do not relay it to task stdout.
        with tempfile.NamedTemporaryFile(mode="w+b", suffix=".log", prefix="datax-", delete=False) as datax_log:
            datax_log_path = Path(datax_log.name)
            datax_log_path.chmod(0o600)
            datax_env = os.environ.copy()
            # DataX 2023 is compiled for Java 8; do not replace the Java runtime
            # used by the DolphinScheduler process itself.
            datax_env["JAVA_HOME"] = os.environ.get("DATAX_JAVA_HOME", "/opt/java/openjdk")
            datax_env["PATH"] = datax_env["JAVA_HOME"] + "/bin:" + datax_env.get("PATH", "")
            return_code = execute_datax(["python3", "/opt/datax/bin/datax.py", str(temp_path)], datax_log, datax_env)
        if return_code:
            print(redact_datax_tail(datax_log_path, claim), file=sys.stderr)
            raise RuntimeError(f"DataX exited with code {return_code}")
        run_transform(claim)
        if not finish(run_id, True, "DataX transfer and transform completed"):
            return 1
        return 0
    except subprocess.TimeoutExpired as error:
        if datax_log_path is not None:
            print(redact_datax_tail(datax_log_path, claim), file=sys.stderr)
        message = "DataX exceeded the 45 minute execution timeout"
        print(f"pipeline failed: {message}", file=sys.stderr)
        if run_id is not None:
            finish(run_id, False, message)
        return 1
    except Exception as error:
        message = str(error)
        print(f"pipeline failed: {message}", file=sys.stderr)
        if run_id is not None:
            finish(run_id, False, message)
        return 1
    finally:
        if temp_path is not None:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass
        if datax_log_path is not None:
            try:
                datax_log_path.unlink(missing_ok=True)
            except OSError:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
