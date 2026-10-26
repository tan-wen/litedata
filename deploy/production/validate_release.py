#!/usr/bin/env python3
"""Developer-only isolated, reduced-memory integration check. Never run against production data.

Usage: py -3 deploy/production/validate_release.py output/production/<fresh-release>
Creates private credentials, disposable test data, and a separate Compose project.
Requires the release scheduler image to have been built first.
"""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('release', type=Path)
    parser.add_argument('--stop', action='store_true')
    args = parser.parse_args()
    root = args.release.resolve()
    if not root.is_relative_to(REPO / 'output/production'):
        raise SystemExit('Use a disposable release under output/production only')
    marker = root / '.validation-only'
    marker.touch()

    def compose(*items, **kwargs):
        return subprocess.run(['docker', 'compose', '-p', 'litedata-release-check',
                               '-f', 'compose.yaml', '-f', 'validation.yaml', *items],
                              cwd=root, check=True, **kwargs)

    if args.stop:
        compose('down')  # Bind data retained for diagnosis; never delete user data.
        return
    if not (root / '.env').exists():
        subprocess.run([sys.executable, 'bin/configure.py'], cwd=root, check=True)
    env = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if '=' in line)
    env.update({'COMPOSE_PROJECT_NAME': 'litedata-release-check', 'DATA_DIR': './validation-data',
                'DOCKER_SUBNET': '172.29.81.0/24', 'DORIS_FE_IP': '172.29.81.10',
                'DORIS_BE_IP': '172.29.81.11', 'PLATFORM_PORT': '28080', 'DOLPHINSCHEDULER_PORT': '22345'})
    (root / '.env').write_text(''.join(f'{k}={v}\n' for k, v in env.items()), encoding='utf-8')
    (root / 'config/mysql.cnf').write_text('[mysqld]\ninnodb_buffer_pool_size=128M\nmax_connections=100\n')
    (root / 'config/be.conf').write_text('mem_limit = 2G\nbase_compaction_num_threads_per_disk = 1\ncumulative_compaction_num_threads_per_disk = 1\n')
    shutil.copy2(REPO / 'deploy/mysql-demo/init.sql', root / 'fixture-source.sql')
    shutil.copy2(REPO / 'deploy/dolphinscheduler/bootstrap-doris.sql', root / 'fixture-doris.sql')
    (root / 'validation.yaml').write_text('''services:
  platform-db:
    mem_limit: 512m
  scheduler-db:
    mem_limit: 512m
  doris-fe:
    cpus: 2
    mem_limit: 1g
    environment:
      DORIS_FE_HEAP: 512m
      JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2
  doris-be:
    cpus: 2
    mem_limit: 2500m
    environment:
      JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2
  registry:
    mem_limit: 384m
    environment:
      JAVA_OPTS: -Xms128m -Xmx192m -XX:ActiveProcessorCount=1
  scheduler-api:
    mem_limit: 800m
    environment:
      JAVA_OPTS: -Xms128m -Xmx320m -XX:ActiveProcessorCount=2
  scheduler-master:
    mem_limit: 640m
    environment:
      JAVA_OPTS: -Xms128m -Xmx320m -XX:ActiveProcessorCount=2
      MASTER_SERVER_LOAD_PROTECTION_ENABLED: 'false'
  scheduler-worker:
    cpus: 2
    mem_limit: 2g
    environment:
      JAVA_OPTS: -Xms128m -Xmx320m -XX:ActiveProcessorCount=2
      WORKER_PHYSICAL_TASK_CONFIG_TASK_EXECUTOR_THREAD_SIZE: '1'
      WORKER_SERVER_LOAD_PROTECTION_ENABLED: 'false'
  scheduler-alert:
    mem_limit: 512m
    environment:
      JAVA_OPTS: -Xms128m -Xmx256m -XX:ActiveProcessorCount=1
  engine-init:
    environment:
      JAVA_OPTS: -Xmx128m
    volumes:
      - ./fixture-doris.sql:/opt/platform/fixture.sql:ro
  platform:
    cpus: 2
    mem_limit: 640m
    environment:
      JAVA_TOOL_OPTIONS: -Xms128m -Xmx320m -XX:ActiveProcessorCount=2
      COOKIE_SECURE: 'false'
  demo-mysql:
    image: mysql:8.4.11
    mem_limit: 512m
    command: [--innodb-buffer-pool-size=128M]
    environment:
      MYSQL_DATABASE: demo_source
      MYSQL_USER: demo_reader
      MYSQL_PASSWORD: demo_reader_password
      MYSQL_ROOT_PASSWORD: disposable-test-root-password
    volumes:
      - ./validation-data/demo:/var/lib/mysql
      - ./fixture-source.sql:/docker-entrypoint-initdb.d/init.sql:ro
    networks: [backend]
    healthcheck:
      test: [CMD-SHELL, 'MYSQL_PWD=$$MYSQL_PASSWORD mysql -h127.0.0.1 -udemo_reader demo_source -e "SELECT COUNT(*) FROM orders" >/dev/null']
      interval: 5s
      timeout: 5s
      retries: 40
''', encoding='utf-8')
    compose('config', '--quiet')
    compose('up', '-d', '--wait', '--wait-timeout', '600', 'platform-db', 'scheduler-db', 'registry',
            'scheduler-api', 'scheduler-master', 'scheduler-worker', 'scheduler-alert', 'doris-fe', 'doris-be', 'demo-mysql')
    compose('run', '--rm', '--no-deps', 'engine-init')
    compose('run', '--rm', '--no-deps', 'engine-init')  # Idempotence with existing token and accounts.
    info = json.loads((root / 'runtime/engine.json').read_text())
    env.update({k: str(info[k]) for k in ('DOLPHINSCHEDULER_PROJECT_CODE', 'DOLPHINSCHEDULER_TOKEN')})
    (root / '.env').write_text(''.join(f'{k}={v}\n' for k, v in env.items()), encoding='utf-8')
    fixture = '''import os, pathlib, pymysql
c=pymysql.connect(host='doris-fe',port=9030,user='root',password=os.environ['DORIS_ROOT_PASSWORD'],autocommit=True)
with c.cursor() as cur:
    for sql in pathlib.Path('/opt/platform/fixture.sql').read_text().split(';'):
        if sql.strip(): cur.execute(sql)
c.close()
'''
    compose('run', '--rm', '--no-deps', '--entrypoint', 'python3', 'engine-init', '-c', fixture)
    compose('up', '-d', '--wait', '--wait-timeout', '300', 'platform')
    spec = importlib.util.spec_from_file_location('smoke', REPO / 'deploy/smoke.py')
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    smoke.load_dotenv = lambda: None  # Never read an existing developer .env.
    import urllib.request
    smoke.build_opener = lambda *handlers: urllib.request.build_opener(urllib.request.ProxyHandler({}), *handlers)
    smoke.BASE_URL = 'http://127.0.0.1:28080/'
    os.environ['PLATFORM_ADMIN_PASSWORD'] = env['PLATFORM_ADMIN_PASSWORD']
    result = smoke.main()
    print(json.dumps(result))
    compose('restart', 'doris-fe', 'doris-be', 'scheduler-worker')
    compose('up', '-d', '--wait', '--wait-timeout', '300', 'doris-fe', 'doris-be', 'scheduler-worker', 'platform')
    persistence = '''import os, time, pymysql
for attempt in range(30):
    try:
        c=pymysql.connect(host='doris-fe',port=9030,user='root',password=os.environ['DORIS_ROOT_PASSWORD'],autocommit=True)
        with c.cursor() as cur:
            for table in ('ods_orders','ads_orders'):
                cur.execute('SELECT COUNT(*) FROM warehouse.'+table)
                assert cur.fetchone()[0] == 2, 'Persisted row count differs'
        c.close()
        break
    except Exception:
        if attempt == 29: raise
        time.sleep(2)
print('Persisted ODS and ADS rows verified after engine restart.')
'''
    compose('run', '--rm', '--no-deps', '--entrypoint', 'python3', 'engine-init', '-c', persistence)
    print('Release integration check passed: init twice, DataX -> Doris -> SQL -> API, credential rotation, persistent restart.')


if __name__ == '__main__':
    main()
