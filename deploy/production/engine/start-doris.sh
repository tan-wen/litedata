#!/usr/bin/env bash
set -euo pipefail
umask 077
role="${1:?fe or be}"
case "$role" in fe|be) ;; *) exit 2 ;; esac
export DORIS_HOME="/opt/apache-doris/$role"
conf="$DORIS_HOME/conf/$role.conf"
# Keep upstream JVM module flags; discard all all-in-one integration-test overrides.
# Save a pristine copy in the container so repeated process starts are deterministic.
[ -f "$conf.upstream" ] || sed '/#### appended by the all-in-one image/,$d' "$conf" > "$conf.upstream"
cp "$conf.upstream" "$conf"
cat /opt/litedata/doris.conf >> "$conf"
printf '\npriority_networks = %s/32\n' "$(hostname -i | awk '{print $1}')" >> "$conf"
if [ "$role" = fe ]; then
  sed -i -E "s/-Xmx[0-9]+[mgMG]/-Xmx${DORIS_FE_HEAP:-8g}/g;s/-Xms[0-9]+[mgMG]/-Xms${DORIS_FE_HEAP:-8g}/g" "$conf"
  # Only applies to a new metadata directory; restart never resets the root account.
  root_hash="$(printf '%s' "${DORIS_ROOT_PASSWORD:?}" | openssl dgst -sha1 -binary | openssl dgst -sha1 | awk '{print toupper($NF)}')"
  printf 'initial_root_password = *%s\n' "$root_hash" >> "$conf"
  mkdir -p "$DORIS_HOME/doris-meta"
else
  sed -i -E 's/-Xmx[0-9]+[mgMG]/-Xmx1g/g' "$conf"
fi
mkdir -p "$DORIS_HOME/log"
child=''
stop() {
  "$DORIS_HOME/bin/stop_$role.sh" --grace || true
  [ -z "$child" ] || wait "$child" || true
}
trap stop TERM INT
"$DORIS_HOME/bin/start_$role.sh" --console &
child=$!
wait "$child"
