#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || sh deploy/setup.sh
docker compose up -d --wait mysql demo-mysql
docker compose exec -T mysql bash /docker-entrypoint-initdb.d/01-scheduler.sh
docker compose up -d --build --wait doris dolphinscheduler
docker compose exec -T dolphinscheduler python3 /opt/platform/bin/bootstrap_engine.py
# engine.json contains only a generated hex token and numeric project code.
code="$(docker compose exec -T dolphinscheduler python3 -c "import json; print(json.load(open('/opt/platform/runtime/engine.json'))['DOLPHINSCHEDULER_PROJECT_CODE'])")"
token="$(docker compose exec -T dolphinscheduler python3 -c "import json; print(json.load(open('/opt/platform/runtime/engine.json'))['DOLPHINSCHEDULER_TOKEN'])")"
sed -i.bak "s|^DOLPHINSCHEDULER_PROJECT_CODE=.*|DOLPHINSCHEDULER_PROJECT_CODE=$code|;s|^DOLPHINSCHEDULER_TOKEN=.*|DOLPHINSCHEDULER_TOKEN=$token|" .env
rm -f .env.bak
docker compose up -d --build --wait platform
echo 'Open http://localhost:8080; admin initial password is stored in .env.'

