#!/usr/bin/env bash
set -euo pipefail
umask 077
role="${1:?scheduler role required}"
if [ "$role" = registry ]; then
  mkdir -p /var/lib/zookeeper/data /var/lib/zookeeper/log
  cat > /tmp/zoo.cfg <<'EOF'
tickTime=2000
dataDir=/var/lib/zookeeper/data
dataLogDir=/var/lib/zookeeper/log
clientPort=2181
maxClientCnxns=100
admin.enableServer=false
4lw.commands.whitelist=ruok,srvr
autopurge.snapRetainCount=3
autopurge.purgeInterval=1
EOF
  exec "$JAVA_HOME/bin/java" ${JAVA_OPTS:-} -cp '/opt/dolphinscheduler/libs/*' org.apache.zookeeper.server.quorum.QuorumPeerMain /tmp/zoo.cfg
fi
if [ "$role" = bootstrap ]; then
  exec python3 /opt/platform/bin/bootstrap.py
fi
case "$role" in api-server|master-server|worker-server|alert-server) ;; *) exit 2 ;; esac
if [ "$role" = worker-server ]; then
  # Default tenant runs as root inside this isolated worker, matching the existing runner.
  # Do not mount Docker socket or host paths other than its private persistent directories.
  python3 - <<'PY'
import json, os
keys = ('PLATFORM_URL', 'PLATFORM_INTERNAL_TOKEN', 'DORIS_WRITER_JDBC_URL', 'DORIS_LOAD_URL',
        'DORIS_WRITER_USER', 'DORIS_WRITER_PASSWORD', 'DATAX_JAVA_HOME', 'DATAX_CHANNELS')
with open('/run/litedata-worker.json', 'w') as f:
    json.dump({k: os.environ[k] for k in keys if k in os.environ}, f)
os.chmod('/run/litedata-worker.json', 0o600)
PY
fi
# Use the upstream classpath and module main class, with exec so PID 1 gets SIGTERM.
sed 's|^\$JAVA_HOME/bin/java |exec $JAVA_HOME/bin/java |' "/opt/dolphinscheduler/$role/bin/start.sh" > "/opt/dolphinscheduler/$role/bin/start-container.sh"
cd "/opt/dolphinscheduler/$role"
exec bash bin/start-container.sh
