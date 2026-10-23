#!/usr/bin/env bash
set -euo pipefail
# Called by MySQL's entrypoint only on a new volume; also safe to invoke explicitly.
# Never import the upstream schema over existing scheduler tables.
password="${DOLPHINSCHEDULER_DB_PASSWORD:?Generate .env using deploy/setup first}"
case "$password" in *[!A-Za-z0-9+/=]*|'') echo 'Scheduler password must use generated Base64 characters' >&2; exit 1 ;; esac
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?}"
mysql --user=root --execute="CREATE DATABASE IF NOT EXISTS dolphinscheduler CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'scheduler'@'%' IDENTIFIED BY '$password';
GRANT ALL ON dolphinscheduler.* TO 'scheduler'@'%';"
tables="$(mysql --user=root --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='dolphinscheduler'")"
if [ "$tables" = "0" ]; then
  mysql --user=root dolphinscheduler < /opt/platform/dolphinscheduler_mysql.sql
else
  mysql --user=root dolphinscheduler --execute="SELECT 1 FROM t_ds_version LIMIT 1" >/dev/null
fi
unset MYSQL_PWD

