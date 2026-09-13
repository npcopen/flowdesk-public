# syntax=docker/dockerfile:1.7
# One reproducible image recipe for the six deployable Spring Boot units.
# SERVICE is the module directory (for example flowdesk-gateway).
FROM node:22-alpine AS frontend
WORKDIR /src
COPY flowdesk-web/package.json flowdesk-web/package-lock.json ./flowdesk-web/
RUN cd flowdesk-web && npm ci --ignore-scripts
COPY flowdesk-web ./flowdesk-web
RUN mkdir -p flowdesk-gateway/src/main/resources/static \
    && cd flowdesk-web \
    && npm run build \
    && npm run build:gateway

FROM maven:3.9.9-eclipse-temurin-17 AS compile
ARG SERVICE
WORKDIR /src
COPY . .
COPY --from=frontend /src/flowdesk-gateway/src/main/resources/static \
    /src/flowdesk-gateway/src/main/resources/static
RUN --mount=type=cache,id=flowdesk-maven-repository,target=/root/.m2/repository,sharing=locked \
    test -n "$SERVICE" \
    && mvn -B \
         -Dmaven.resolver.transport=wagon \
         -Dmaven.wagon.http.retryHandler.count=3 \
         -pl "$SERVICE" -am package -DskipTests \
    && artifact="$(find "$SERVICE/target" -maxdepth 1 -type f -name '*-exec.jar' | head -n 1)" \
    && if [ -z "$artifact" ]; then \
         artifact="$(find "$SERVICE/target" -maxdepth 1 -type f -name '*.jar' \
           ! -name 'original-*.jar' ! -name '*-plain.jar' | head -n 1)"; \
       fi \
    && test -n "$artifact" \
    && mkdir -p /out \
    && cp "$artifact" /out/app.jar

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system flowdesk \
    && useradd --system --gid flowdesk --home-dir /nonexistent --shell /usr/sbin/nologin flowdesk
WORKDIR /app
COPY --from=compile /out/app.jar /app/app.jar
RUN chown -R flowdesk:flowdesk /app
# Docker copies the image mountpoint ownership into a newly-created named
# volume.  Pre-create the service-token directory so the non-root IAM process
# can rotate short-lived JWTs while downstream services keep it read-only.
RUN mkdir -p /run/flowdesk/service-tokens \
    && chown -R flowdesk:flowdesk /run/flowdesk
USER flowdesk
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
