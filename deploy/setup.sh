#!/usr/bin/env sh
set -eu
[ ! -e .env ] || { echo '.env already exists; refusing to overwrite it.' >&2; exit 1; }
cp .env.example .env
secret() { openssl rand -base64 "${1:-32}" | tr -d '\n'; }
replace() { key="$1"; value="$2"; sed -i.bak "s|^${key}=.*|${key}=${value}|" .env; rm -f .env.bak; }
replace MYSQL_ROOT_PASSWORD "$(secret 24)"
replace DOLPHINSCHEDULER_DB_PASSWORD "$(secret 24)"
replace PLATFORM_DB_PASSWORD "$(secret 24)"
replace PLATFORM_ENCRYPTION_KEY "$(secret 32)"
replace PLATFORM_ADMIN_PASSWORD "$(secret 24)"
replace PLATFORM_INTERNAL_TOKEN "$(secret 32)"
replace DOLPHINSCHEDULER_ADMIN_PASSWORD dolphinscheduler123
replace DORIS_PASSWORD "$(secret 24)"
replace DORIS_WRITER_PASSWORD "$(secret 24)"
echo 'Created .env with random Base64 secrets. Run: docker compose up -d --build'
