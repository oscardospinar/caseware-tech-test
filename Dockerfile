# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY config ./config
COPY src ./src
# CI runs verify before building. Keep build tools and test classes out of the runtime image.
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --gid 10001 fanout \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin fanout
COPY --from=build --chown=10001:10001 /workspace/target/template-fanout-worker-1.0.0.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError"
STOPSIGNAL SIGTERM
# No HTTP listener. Polling stays active until Java (PID 1) receives SIGTERM.
# The finite local demo requires FANOUT_DEMO_ENABLED=true explicitly.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
