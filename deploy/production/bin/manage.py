#!/usr/bin/env python3
"""Linux deployment operations. Commands never print rendered Compose secrets."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

from configure import ROOT, read_env, validate, write_env


def compose(*args, **kwargs):
    return subprocess.run(['docker', 'compose', '--env-file', str(ROOT / '.env'), *args],
                          cwd=ROOT, check=True, **kwargs)


def verify():
    count = 0
    for line in (ROOT / 'SHA256SUMS').read_text().splitlines():
        digest, filename = line.split('  ', 1)
        path = (ROOT / filename).resolve()
        if not path.is_relative_to(ROOT):
            raise SystemExit('Invalid checksum path')
        digest_object = hashlib.sha256()
        with path.open('rb') as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b''):
                digest_object.update(chunk)
        actual = digest_object.hexdigest()
        if digest != actual:
            raise SystemExit('Checksum mismatch: ' + filename)
        count += 1
    print(f'Checksums OK ({count} files).')


def preflight():
    verify()
    values = read_env()
    validate(values)
    compose('config', '--quiet')
    info = json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .}}']))
    if info.get('OSType') != 'linux' or info.get('Architecture') not in ('x86_64', 'amd64'):
        raise SystemExit('Requires Linux x86_64 Docker engine')
    if info.get('NCPU', 0) < 32 or info.get('MemTotal', 0) < 120 * 1024**3:
        raise SystemExit('Medium preset requires 32 CPUs / 128GB RAM (at least 120GiB visible to Docker)')
    if Path('/proc/sys/vm/max_map_count').exists():
        if int(Path('/proc/sys/vm/max_map_count').read_text()) < 2000000:
            raise SystemExit('Set vm.max_map_count=2000000 before startup')
    print('Preflight OK. Confirm SSD capacity and TLS ingress using README before exposing the platform.')


def start():
    preflight()
    if not (ROOT / 'runtime/engine.json').exists():
        raise SystemExit('First install: run python3 bin/manage.py install')
    compose('up', '-d', '--wait', '--wait-timeout', '600')


def install(offline=False):
    preflight()
    if not offline:
        compose('build', 'scheduler-api')
    compose('up', '-d', '--wait', '--wait-timeout', '600', 'platform-db', 'scheduler-db',
            'doris-fe', 'doris-be', 'registry', 'scheduler-api', 'scheduler-master',
            'scheduler-worker', 'scheduler-alert')
    compose('run', '--rm', '--no-deps', 'engine-init')
    engine = json.loads((ROOT / 'runtime/engine.json').read_text())
    values = read_env()
    values.update({k: str(engine[k]) for k in ('DOLPHINSCHEDULER_TOKEN', 'DOLPHINSCHEDULER_PROJECT_CODE')})
    write_env(values)
    compose('up', '-d', '--wait', '--wait-timeout', '600', 'platform')
    print('Installation complete. Configure HTTPS ingress; credentials are in the private .env.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['verify', 'preflight', 'install', 'install-offline', 'start', 'stop', 'status', 'backup-metadata'])
    action = parser.parse_args().action
    if action == 'verify':
        verify()
    elif action == 'preflight':
        preflight()
    elif action == 'install':
        install()
    elif action == 'install-offline':
        install(offline=True)
    elif action == 'start':
        start()
    elif action == 'stop':
        compose('stop')
    elif action == 'status':
        compose('ps')
    elif action == 'backup-metadata':
        from datetime import datetime
        destination = ROOT / 'backups' / datetime.now().strftime('%Y%m%d-%H%M%S')
        destination.mkdir(mode=0o700, parents=True)
        for service, db in (('platform-db', 'platform'), ('scheduler-db', 'dolphinscheduler')):
            with (destination / (db + '.sql')).open('wb') as f:
                compose('exec', '-T', service, 'sh', '-c',
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction '
                        '--routines --events --triggers --no-tablespaces --set-gtid-purged=OFF ' + db, stdout=f)
            os.chmod(destination / (db + '.sql'), 0o600)
        shutil.copy2(ROOT / '.env', destination / '.env')
        if (ROOT / 'runtime/engine.json').exists():
            shutil.copy2(ROOT / 'runtime/engine.json', destination / 'engine.json')
        print('Metadata backup saved privately. Copy off-host; Doris data is NOT included. See README.')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit('Deployment command failed, exit=' + str(error.returncode))
