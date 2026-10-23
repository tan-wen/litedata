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
# DS 3.4.0 ships an INT column for a Long workflow code (upstream #17988).
# Widen only this known column; preserve existing metadata and upstream schema.
serial_code_type="$(mysql --user=root --batch --skip-column-names --execute="SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema='dolphinscheduler' AND table_name='t_ds_serial_command' AND column_name='workflow_definition_code'")"
case "$serial_code_type" in
  int)
    mysql --user=root dolphinscheduler --execute="ALTER TABLE t_ds_serial_command MODIFY COLUMN workflow_definition_code BIGINT NOT NULL COMMENT 'workflow definition code'"
    echo 'Applied DolphinScheduler serial workflow code BIGINT compatibility fix.'
    ;;
  bigint) ;;
  *) echo 'Unexpected scheduler serial workflow code schema; inspect before continuing.' >&2; exit 1 ;;
esac
unset MYSQL_PWD

