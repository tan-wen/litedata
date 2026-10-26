# Platform is deployed as app/litedata.jar. This image contains scheduler/runtime only.
ARG BASE_IMAGE=apache/dolphinscheduler-standalone-server:3.4.0
FROM ${BASE_IMAGE}
USER root
RUN (python3 -c 'import pymysql, cryptography' && command -v curl >/dev/null) \
    || (apt-get update && apt-get install -y --no-install-recommends python3 python3-pymysql python3-cryptography curl ca-certificates && rm -rf /var/lib/apt/lists/*)
ADD artifacts/engine-runtime.tar.gz /opt/
RUN for module in api-server master-server worker-server alert-server tools; do \
      cp /opt/litedata-runtime/mysql-connector-j-8.4.0.jar /opt/dolphinscheduler/$module/libs/; \
    done \
    && mkdir -p /opt/dolphinscheduler/plugins/task-plugins /opt/platform/bin \
    && cp /opt/litedata-runtime/dolphinscheduler-task-shell.jar /opt/dolphinscheduler/plugins/task-plugins/
COPY engine/*.py engine/start-scheduler.sh /opt/platform/bin/
ENV DATAX_JAVA_HOME=/opt/java/openjdk
ENTRYPOINT ["bash", "/opt/platform/bin/start-scheduler.sh"]
