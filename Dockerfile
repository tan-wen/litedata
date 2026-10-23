# Build inside the container so `docker compose up --build` works from a clean checkout.
FROM docker.io/library/eclipse-temurin:25-jre AS build
WORKDIR /workspace
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates tar openjdk-25-jdk \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSLo /tmp/maven.tar.gz https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz \
    && echo '4b7195b6a4f5c81af4c0212677a32ee8143643401bc6e1e8412e6b06ea82beac  /tmp/maven.tar.gz' | sha256sum -c - \
    && mkdir -p /opt/maven \
    && tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1
ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 /opt/maven/bin/mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 /opt/maven/bin/mvn -B -ntp -DskipTests package

FROM docker.io/library/eclipse-temurin:25-jre
WORKDIR /opt/platform
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/platform/app.jar"]
