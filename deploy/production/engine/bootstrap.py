"""Idempotent fresh-install bootstrap. No demo tables and no credentials on stdout."""
import datetime
import http.cookiejar
import json
import os
import secrets
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

import pymysql


def main():
    connection = pymysql.connect(host='doris-fe', port=9030, user='root',
                                 password=os.environ['DORIS_ROOT_PASSWORD'],
                                 connect_timeout=15, read_timeout=60, autocommit=True)
    try:
        with connection.cursor(pymysql.cursors.DictCursor) as cursor:
            cursor.execute('SHOW BACKENDS')
            address = os.environ['DORIS_BE_IP']
            nodes = cursor.fetchall()
            if not any(node['Host'] == address for node in nodes):
                # IP comes from validated configure.py, never from SQL/job content.
                import ipaddress
                ipaddress.ip_address(address)
                cursor.execute(f'ALTER SYSTEM ADD BACKEND "{address}:9050"')
            for _ in range(60):
                cursor.execute('SHOW BACKENDS')
                if any(n['Host'] == address and str(n['Alive']).lower() == 'true' for n in cursor.fetchall()):
                    break
                time.sleep(2)
            else:
                raise RuntimeError('Doris BE did not become Alive')
            cursor.execute('CREATE DATABASE IF NOT EXISTS warehouse')
            for user, key, grants in (
                ('platform_reader', 'DORIS_PASSWORD', 'SELECT_PRIV'),
                ('platform_writer', 'DORIS_WRITER_PASSWORD', 'SELECT_PRIV,LOAD_PRIV,CREATE_PRIV'),
            ):
                # Do not silently reset existing user passwords during service restart.
                cursor.execute('CREATE USER IF NOT EXISTS %s IDENTIFIED BY %s', (user, os.environ[key]))
                cursor.execute('GRANT ' + grants + ' ON warehouse.* TO %s', (user,))
            # The upstream distribution also creates an admin account with an empty password.
            cursor.execute("SET PASSWORD FOR 'admin' = PASSWORD(%s)", (os.environ['DORIS_ROOT_PASSWORD'],))
    finally:
        connection.close()

    opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base = 'http://scheduler-api:12345/dolphinscheduler'
    session_id = None

    def request(path, values=None, token=None):
        data = None if values is None else urlencode(values).encode()
        headers = {'token': token} if token else ({'sessionId': session_id} if session_id else {})
        with opener.open(Request(base + path, data=data, headers=headers), timeout=30) as response:
            result = json.load(response)
        if result.get('code') != 0:
            raise RuntimeError('Scheduler bootstrap failed at ' + path + ', code=' + str(result.get('code')))
        return result.get('data')

    session_id = request('/login', {'userName': 'admin', 'userPassword': os.environ['DOLPHINSCHEDULER_ADMIN_PASSWORD']})['sessionId']
    user = request('/users/get-user-info')
    projects = [p for p in request('/projects/list') if p['name'] == 'platform']
    if not projects:
        request('/projects', {'projectName': 'platform', 'description': 'LiteData production workflows'})
        projects = [p for p in request('/projects/list') if p['name'] == 'platform']
    path = Path('/opt/platform/runtime/engine.json')
    if path.exists():
        saved = json.loads(path.read_text())
        request('/projects/list', token=saved['DOLPHINSCHEDULER_TOKEN'])
        if saved['DOLPHINSCHEDULER_PROJECT_CODE'] != projects[0]['code']:
            raise RuntimeError('Saved project code differs; inspect metadata before continuing')
        print('Existing scheduler token validated; Doris accounts and BE ready.')
        return
    token = secrets.token_hex(32)
    expires = (datetime.datetime.now() + datetime.timedelta(days=365)).strftime('%Y-%m-%d %H:%M:%S')
    request('/access-tokens', {'userId': user['id'], 'expireTime': expires, 'token': token})
    request('/projects/list', token=token)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as handle:
        json.dump({'DOLPHINSCHEDULER_PROJECT_CODE': projects[0]['code'],
                   'DOLPHINSCHEDULER_TOKEN': token, 'expiresAt': expires}, handle)
    print('Doris accounts/BE and scheduler project/token initialized; no demo data created.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Drivers may include SQL, URLs or secrets in their exception messages.
        raise SystemExit('Engine bootstrap failed (' + type(error).__name__ + '); inspect configuration and engine health.')
