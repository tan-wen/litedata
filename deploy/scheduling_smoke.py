#!/usr/bin/env python3
"""Read-only verification of the deployed scheduling pages and real DS node logs."""
import argparse
import json
import os
import re
import sys
from html import unescape
from pathlib import Path
from urllib.parse import urlencode, urlparse
from smoke import BASE_URL, Client, SmokeFailure, load_dotenv, post_form


def checked(client, path):
    status, body = client.request("GET", path)
    if status != 200:
        raise SmokeFailure(f"scheduling endpoint returned HTTP {status}: {path}")
    return body


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--browser-state", action="store_true", help="save an ignored local browser session for UI verification")
    args = parser.parse_args()
    load_dotenv()
    client = Client()
    _, login = client.request("GET", "/login")
    status, body = post_form(client, login, "/login", {"username": "admin", "password": os.environ["PLATFORM_ADMIN_PASSWORD"]})
    if status != 200 or "退出登录" not in body:
        raise SmokeFailure("platform login failed")
    for cron in ("0 0 * * * ?", "0 */5 * * * ?", "0 0 2 * * ?"):
        result = json.loads(checked(client, "/scheduling/preview?" + urlencode({"cron": cron})))
        if result.get("timezone") != "Asia/Shanghai" or len(result.get("nextTimes", [])) != 3:
            raise SmokeFailure("schedule preview is invalid")
    verified = {}
    unavailable = 0
    for kind, listing in (("transform", "/transforms"), ("pipeline", "/pipelines")):
        if len(verified) == 2:
            break
        html = checked(client, listing)
        resources = list(dict.fromkeys(re.findall(r'/scheduling/' + kind + r'/\d+', html)))
        for resource in resources[:20]:
            instances = checked(client, resource)
            details = re.findall(re.escape(resource) + r'/instances/\d+', instances)
            if not details:
                continue
            for detail_path in list(dict.fromkeys(details))[:3]:
                detail = checked(client, detail_path)
                for href, label in re.findall(r'<a\b[^>]*href="([^"]+\?taskId=\d+)"[^>]*>(.*?)</a>', detail, re.S):
                    node_kind = "pipeline" if kind == "pipeline" or "同步：" in unescape(label) else "transform"
                    if node_kind in verified:
                        continue
                    node_page = checked(client, unescape(href))
                    match = re.search(r'data-log-url="([^"]+)"', node_page)
                    if not match:
                        continue
                    path = unescape(match.group(1))
                    status, body = client.request("GET", path + "?offset=0&limit=20")
                    if status == 503:
                        unavailable += 1
                        continue  # DS may no longer retain a historical node's log.
                    if status != 200:
                        raise SmokeFailure("node log authorization or route check failed")
                    first = json.loads(body)
                    if first.get("offset") != 0 or first.get("lineCount", 0) <= 0:
                        continue
                    for key in ("PLATFORM_ADMIN_PASSWORD", "PLATFORM_INTERNAL_TOKEN", "DOLPHINSCHEDULER_TOKEN", "DORIS_PASSWORD", "DORIS_WRITER_PASSWORD", "PLATFORM_ENCRYPTION_KEY"):
                        secret = os.environ.get(key, "")
                        if secret and secret in first["content"]:
                            raise SmokeFailure("node log exposed a configured credential")
                    following = json.loads(checked(client, path + "?" + urlencode({"offset": first["nextOffset"], "limit": 20})))
                    if following.get("offset") != first["nextOffset"]:
                        raise SmokeFailure("node log continuation offset is invalid")
                    verified[node_kind] = {"instancePath": unescape(href), "linesRead": first["lineCount"] + following["lineCount"]}
                if len(verified) == 2:
                    break
            if len(verified) == 2:
                break
    if len(verified) != 2:
        raise SmokeFailure("need retained logs for an executed sync node and SQL node (a SQL chain can contain both)")
    if args.browser_state:
        cookies = []
        for handler in client.opener.handlers:
            for cookie in getattr(handler, "cookiejar", []):
                cookies.append({"name": cookie.name, "value": cookie.value, "domain": urlparse(BASE_URL).hostname,
                                "path": cookie.path, "expires": -1, "httpOnly": True, "secure": cookie.secure, "sameSite": "Lax"})
        output = Path(__file__).resolve().parents[1] / "output" / "playwright"
        output.mkdir(parents=True, exist_ok=True)
        (output / "storage-state.json").write_text(json.dumps({"cookies": cookies, "origins": []}), encoding="utf-8")
        (output / "scheduling-smoke-result.json").write_text(json.dumps(verified), encoding="utf-8")
    print(json.dumps({"schedulePreviews": 3, "realLogs": verified, "unavailableHistoricalLogs": unavailable}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except SmokeFailure as failure:
        # SmokeFailure messages are controlled constants; never include response bodies.
        print("Scheduling smoke failed: " + str(failure), file=sys.stderr)
        raise SystemExit(1)
    except (KeyError, ValueError):
        print("Scheduling smoke failed; check deployment and existing workflow/log availability", file=sys.stderr)
        raise SystemExit(1)
