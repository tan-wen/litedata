# DataX has no maintained official container image. Build the official Apache-2.0 source
# at a pinned commit with the supported JDBC readers and doriswriter in the worker.
ARG DOLPHINSCHEDULER_VERSION=3.4.0
FROM apache/dolphinscheduler-standalone-server:${DOLPHINSCHEDULER_VERSION} AS datax-builder
ARG DATAX_REF=9a1f88751e24314b083a74f1b83ef56d69ce98bd
ARG MYSQL_CONNECTOR_VERSION=8.4.0
WORKDIR /src
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSLo /tmp/maven.tar.gz https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz \
    && echo '4b7195b6a4f5c81af4c0212677a32ee8143643401bc6e1e8412e6b06ea82beac  /tmp/maven.tar.gz' | sha256sum -c - \
    && mkdir -p /opt/maven \
    && tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1
RUN git init . \
    && git remote add origin https://github.com/alibaba/DataX.git \
    && git fetch --depth 1 origin "${DATAX_REF}" \
    && git checkout --detach FETCH_HEAD \
    && find . -path '*/src/main/assembly/package.xml' -exec sed -i -E 's|<id>[[:space:]]*</id>|<id>datax</id>|g;s|<id[[:space:]]*/>|<id>datax</id>|g' {} + \
    && sed -i 's|<finalName>datax</finalName>|<finalName>datax</finalName><appendAssemblyId>false</appendAssemblyId>|g' core/pom.xml transformer/pom.xml mysqlreader/pom.xml postgresqlreader/pom.xml sqlserverreader/pom.xml oraclereader/pom.xml doriswriter/pom.xml \
    && sed -i 's|<version>42.3.3</version>|<version>42.7.8</version>|g' postgresqlreader/pom.xml \
    && sed -i 's|<artifactId>sqljdbc4</artifactId>|<artifactId>mssql-jdbc</artifactId>|g;s|<version>4.0</version>|<version>12.10.1.jre8</version>|g' sqlserverreader/pom.xml \
    && sed -i 's|<groupId>com.oracle</groupId>|<groupId>com.oracle.database.jdbc</groupId>|g;s|<artifactId>ojdbc6</artifactId>|<artifactId>ojdbc8</artifactId>|g;s|<version>11.2.0.3</version>|<version>23.9.0.25.07</version>|g' oraclereader/pom.xml
RUN --mount=type=cache,target=/root/.m2 /opt/maven/bin/mvn -B -ntp -DskipTests clean package -pl core,transformer,mysqlreader,postgresqlreader,sqlserverreader,oraclereader,doriswriter -am \
    && test -f core/target/datax/bin/datax.py \
    && test -f mysqlreader/target/datax/plugin/reader/mysqlreader/plugin.json \
    && test -f postgresqlreader/target/datax/plugin/reader/postgresqlreader/plugin.json \
    && test -f sqlserverreader/target/datax/plugin/reader/sqlserverreader/plugin.json \
    && test -f oraclereader/target/datax/plugin/reader/oraclereader/plugin.json \
    && test -f doriswriter/target/datax/plugin/writer/doriswriter/plugin.json
RUN curl -fsSLo /tmp/mysql-connector-j.jar "https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/${MYSQL_CONNECTOR_VERSION}/mysql-connector-j-${MYSQL_CONNECTOR_VERSION}.jar" \
    && echo "d77962877d010777cff997015da90ee689f0f4bb76848340e1488f2b83332af5  /tmp/mysql-connector-j.jar" | sha256sum -c - \
    && find mysqlreader/target/datax/plugin/reader/mysqlreader/libs -name 'mysql-connector-java-*.jar' -delete \
    && cp /tmp/mysql-connector-j.jar mysqlreader/target/datax/plugin/reader/mysqlreader/libs/mysql-connector-j-${MYSQL_CONNECTOR_VERSION}.jar

FROM apache/dolphinscheduler-standalone-server:${DOLPHINSCHEDULER_VERSION}
ARG DOLPHINSCHEDULER_VERSION=3.4.0
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pymysql python3-cryptography curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/platform/bin /opt/platform/jobs /opt/datax
# The standalone image does not ship the SHELL TaskChannel. Both workflow
# validation and worker execution need the matching shaded task plugin.
RUN mkdir -p /opt/dolphinscheduler/plugins/task-plugins \
    && curl -fsSLo /opt/dolphinscheduler/plugins/task-plugins/dolphinscheduler-task-shell.jar \
       "https://repo.maven.apache.org/maven2/org/apache/dolphinscheduler/dolphinscheduler-task-shell/${DOLPHINSCHEDULER_VERSION}/dolphinscheduler-task-shell-${DOLPHINSCHEDULER_VERSION}-shade.jar" \
    && echo '792c1633c18d5d63456aa3d370321fa98cbfb501840d1e5f5e0805ed099ae8dc  /opt/dolphinscheduler/plugins/task-plugins/dolphinscheduler-task-shell.jar' | sha256sum -c -
COPY --from=datax-builder /src/core/target/datax/ /opt/datax/
COPY --from=datax-builder /src/transformer/target/datax/ /opt/datax/
COPY --from=datax-builder /src/mysqlreader/target/datax/ /opt/datax/
COPY --from=datax-builder /src/postgresqlreader/target/datax/ /opt/datax/
COPY --from=datax-builder /src/sqlserverreader/target/datax/ /opt/datax/
COPY --from=datax-builder /src/oraclereader/target/datax/ /opt/datax/
COPY --from=datax-builder /src/doriswriter/target/datax/ /opt/datax/
COPY --from=datax-builder /tmp/mysql-connector-j.jar /opt/dolphinscheduler/standalone-server/libs/mysql-connector-j-8.4.0.jar
COPY deploy/dolphinscheduler/run_pipeline.py /opt/platform/bin/run_pipeline.py
COPY deploy/dolphinscheduler/run_sql_task.py /opt/platform/bin/run_sql_task.py
COPY deploy/dolphinscheduler/bootstrap_engine.py /opt/platform/bin/bootstrap_engine.py
COPY deploy/dolphinscheduler/start_worker.sh /opt/platform/bin/start_worker.sh
COPY deploy/dolphinscheduler/bootstrap-doris.sql /opt/platform/bootstrap-doris.sql
ENV DATAX_JAVA_HOME=/opt/java/openjdk
RUN test -x /opt/java/openjdk/bin/java \
    && chmod 0755 /opt/platform/bin/run_pipeline.py /opt/datax/bin/datax.py
CMD ["/bin/bash", "/opt/platform/bin/start_worker.sh"]
