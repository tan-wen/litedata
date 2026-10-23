#!/usr/bin/env bash
set -euo pipefail
umask 077
# DS may use sudo -i to enter the default tenant. Preserve the worker's
# private configuration in a root-only file instead of embedding it in DS tasks.
python3 - <<'PY'
import json, os
keys = ("PLATFORM_URL", "PLATFORM_INTERNAL_TOKEN", "DORIS_WRITER_JDBC_URL", "DORIS_LOAD_URL",
        "DORIS_WRITER_USER", "DORIS_WRITER_PASSWORD", "DATAX_JAVA_HOME", "DATAX_CHANNELS")
values = {key: os.environ[key] for key in keys if key in os.environ}
for required in ("PLATFORM_INTERNAL_TOKEN", "DORIS_WRITER_USER", "DORIS_WRITER_PASSWORD"):
    if not values.get(required):
        raise RuntimeError("Missing worker environment: " + required)
fd = os.open("/run/litedata-worker.json", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as handle:
    json.dump(values, handle)
PY
exec /bin/bash /opt/dolphinscheduler/standalone-server/bin/start.sh

