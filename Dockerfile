# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-26 AS build
ARG VERSION=0.1.0-SNAPSHOT
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -Denforcer.skip -Drevision=${VERSION}
COPY contracts/upstream-tools.json contracts/tool-classification.json contracts/tool-search.json contracts/
COPY src/main src/main
# Tests and the quality profile (format, Error Prone) run in CI; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -P '!quality' -Drevision=${VERSION} \
    && cp target/librenms-mcp-${VERSION}.jar target/app.jar

# A trimmed runtime: the modules jdeps reports for the app and its dependencies, plus
# java.instrument so an OpenTelemetry agent can be attached.
RUN jlink --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.rmi,java.security.jgss,java.sql,java.xml,jdk.httpserver,jdk.management,jdk.unsupported \
          --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /opt/jre

# Distroless base: glibc and CA certificates, no shell or package manager; runs as uid 65532.
FROM gcr.io/distroless/base-debian13:nonroot
COPY --from=build /opt/jre /opt/jre
COPY --from=build /src/target/lib/ /app/lib/
COPY --from=build /src/target/app.jar /app/app.jar
WORKDIR /app

ENV PATH=/opt/jre/bin \
    MCP_TRANSPORT=http \
    MCP_HTTP_HOST=0.0.0.0 \
    MCP_HTTP_PORT=8000 \
    LOG_FORMAT=json
EXPOSE 8000
USER 65532:65532

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
  CMD ["/opt/jre/bin/java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xmx32m", "-cp", "/app/app.jar", "ca.int13.librenmsmcp.HealthCheck"]

# The retry-limit property disables the JDK HTTP client's hidden GET retries (see LibreNmsClient).
ENTRYPOINT ["/opt/jre/bin/java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "-Djdk.httpclient.redirects.retrylimit=1", "-jar", "/app/app.jar"]
