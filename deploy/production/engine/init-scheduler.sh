#!/usr/bin/env bash
set -euo pipefail
# MySQL entrypoint runs only for an empty datadir, against its private socket server.
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mysql -uroot dolphinscheduler < /opt/litedata/schema.sql
mysql -uroot dolphinscheduler -e "ALTER TABLE t_ds_serial_command MODIFY COLUMN workflow_definition_code BIGINT NOT NULL"
# Set a random initial administrator password before the API service can start.
# Generated values use hex, so they are safe in this SQL literal.
case "$DOLPHINSCHEDULER_ADMIN_PASSWORD" in *[!a-f0-9]*|'') echo 'DS admin secret must be generated hex' >&2; exit 1 ;; esac
mysql -uroot dolphinscheduler -e "UPDATE t_ds_user SET user_password=MD5('$DOLPHINSCHEDULER_ADMIN_PASSWORD') WHERE user_name='admin'"
