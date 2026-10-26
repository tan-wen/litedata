#!/usr/bin/env python3
"""Create one private configuration; never overwrite a deployed encryption key."""
import base64
import ipaddress
import os
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECRET_KEYS = (
    'MYSQL_ROOT_PASSWORD', 'SCHEDULER_MYSQL_ROOT_PASSWORD', 'PLATFORM_DB_PASSWORD',
    'DOLPHINSCHEDULER_DB_PASSWORD', 'PLATFORM_ADMIN_PASSWORD', 'PLATFORM_INTERNAL_TOKEN',
    'DOLPHINSCHEDULER_ADMIN_PASSWORD', 'DORIS_ROOT_PASSWORD', 'DORIS_PASSWORD', 'DORIS_WRITER_PASSWORD',
)


def read_env():
    return dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines()
                if line.strip() and not line.startswith('#') and '=' in line)


def write_env(values, exclusive=False):
    # Atomic replacement; write secrets with mode 0600 before the first byte.
    path = ROOT / '.env'
    if exclusive and path.exists():
        raise SystemExit('.env already exists; refusing to replace credentials.')
    temporary = ROOT / '.env.tmp'
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as handle:
            for key, value in values.items():
                if '\n' in str(value) or '\r' in str(value):
                    raise ValueError('Environment values must be single-line')
                handle.write(f'{key}={value}\n')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def validate(values):
    for key in SECRET_KEYS:
        if len(values.get(key, '')) < 32:
            raise SystemExit('Missing/short generated secret: ' + key)
    try:
        if len(base64.b64decode(values['PLATFORM_ENCRYPTION_KEY'], validate=True)) != 32:
            raise ValueError()
    except (ValueError, KeyError):
        raise SystemExit('PLATFORM_ENCRYPTION_KEY must encode exactly 32 bytes')
    network = ipaddress.ip_network(values['DOCKER_SUBNET'])
    for key in ('DORIS_FE_IP', 'DORIS_BE_IP'):
        if ipaddress.ip_address(values[key]) not in network:
            raise SystemExit(key + ' must belong to DOCKER_SUBNET')
    if values['DORIS_FE_IP'] == values['DORIS_BE_IP']:
        raise SystemExit('FE and BE must have distinct static IPs')


def main():
    if (ROOT / '.env').exists():
        raise SystemExit('.env already exists; refusing to replace credentials.')
    values = dict(line.split('=', 1) for line in (ROOT / '.env.example').read_text().splitlines()
                  if line and not line.startswith('#') and '=' in line)
    values.update({key: secrets.token_hex(24) for key in SECRET_KEYS})
    values['PLATFORM_ENCRYPTION_KEY'] = base64.b64encode(secrets.token_bytes(32)).decode()
    validate(values)
    write_env(values, exclusive=True)
    for directory in ('data', 'runtime', 'backups'):
        (ROOT / directory).mkdir(mode=0o700, exist_ok=True)
    print('Created private .env with independent random credentials. Keep its encryption key backed up.')


if __name__ == '__main__':
    main()
