#!/usr/bin/env python3
"""Initialize fixed engine identities and return DS configuration without printing secrets."""
import datetime
import http.cookiejar
import json
import os
import secrets
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
import pymysql

def main():
    connection = pymysql.connect(host="doris", port=9030, user="root", password="",
                                 connect_timeout=15, read_timeout=60, autocommit=True)
    try:
        with connection.cursor() as cursor:
            for statement in Path("/opt/platform/bootstrap-doris.sql").read_text().split(";"):
                if statement.strip():
                    cursor.execute(statement)
            accounts = [
                (os.environ["DORIS_USER"], os.environ["DORIS_PASSWORD"], "SELECT_PRIV"),
                (os.environ["DORIS_WRITER_USER"], os.environ["DORIS_WRITER_PASSWORD"], "SELECT_PRIV,LOAD_PRIV,CREATE_PRIV"),
            ]
            for user, password, grants in accounts:
                if user not in ("platform_reader", "platform_writer"):
                    raise ValueError("Use the fixed platform_reader/platform_writer identities")
                cursor.execute("CREATE USER IF NOT EXISTS %s IDENTIFIED BY %s", (user, password))
                cursor.execute("SET PASSWORD FOR %s = PASSWORD(%s)", (user, password))
                cursor.execute("GRANT " + grants + " ON warehouse.* TO %s", (user,))
            for table in ("ods_orders", "ads_orders"):
                cursor.execute("SHOW CREATE TABLE warehouse." + table)
                if "UNIQUE KEY" not in cursor.fetchone()[1].upper():
                    raise RuntimeError("Target tables must use UNIQUE KEY")
    finally:
        connection.close()

    opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base = "http://127.0.0.1:12345/dolphinscheduler"
    session_id = None
    def request(path, values=None, token=None):
        data = None if values is None else urlencode(values).encode()
        headers = {} if token is None else {"token": token}
        if token is None and session_id:
            headers["sessionId"] = session_id
        with opener.open(Request(base + path, data=data, headers=headers), timeout=30) as response:
            result = json.load(response)
        if result.get("code") != 0:
            raise RuntimeError("DS initialization request failed at " + path + " code=" + str(result.get("code")))
        return result.get("data")

    login = request("/login", {"userName": "admin", "userPassword": os.environ["DOLPHINSCHEDULER_ADMIN_PASSWORD"]})
    session_id = login["sessionId"]
    user = request("/users/get-user-info")
    projects = request("/projects/list")
    matches = [item for item in projects if item.get("name") == "platform"]
    if not matches:
        request("/projects", {"projectName": "platform", "description": "LiteData pipelines"})
        matches = [item for item in request("/projects/list") if item.get("name") == "platform"]
    if not matches:
        raise RuntimeError("DS project was not created")
    token = secrets.token_hex(32)
    expires = (datetime.datetime.now() + datetime.timedelta(days=365)).strftime("%Y-%m-%d %H:%M:%S")
    request("/access-tokens", {"userId": user["id"], "expireTime": expires, "token": token})
    request("/projects/list", token=token)
    result = {"DOLPHINSCHEDULER_PROJECT_CODE": matches[0]["code"], "DOLPHINSCHEDULER_TOKEN": token}
    path = Path("/opt/platform/runtime/engine.json")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        json.dump(result, handle)
    print("Doris identities/tables and DolphinScheduler project/token initialized.")

if __name__ == "__main__":
    main()

