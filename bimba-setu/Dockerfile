# syntax=docker/dockerfile:1.6
# BIMBA Setu: React + Vite console -> Spring Boot jar (Java 21) -> JRE image.
# Config is mounted at /config, the disk buffer + HTTPS key store live in /data.
# Multi-arch: the UI and jar are built on the build machine's CPU, only the runtime image is per CPU, e.g.
#   docker buildx build --platform linux/amd64,linux/arm64 -t bimba-setu .   (arm64 = Raspberry Pi 4/5, 64-bit OS)

FROM --platform=$BUILDPLATFORM node:22-alpine AS ui
WORKDIR /ui
COPY ui/package.json ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY ui/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM gradle:9.2-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY collect-lib ./collect-lib
COPY edge/build.gradle ./edge/build.gradle
COPY agent/build.gradle ./agent/build.gradle
COPY src ./src
COPY --from=ui /ui/dist ./src/main/resources/static
RUN --mount=type=cache,target=/home/gradle/.gradle gradle bootJar --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home /opt/bimba-setu bimba \
 && mkdir -p /config /data /opt/bimba-setu && chown bimba /data
COPY --from=build /src/build/libs/bimba-setu.jar /opt/bimba-setu/bimba-setu.jar
ENV BIMBA_CONFIG_DIR=/config BIMBA_DATA_DIR=/data TZ=UTC JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
USER bimba
WORKDIR /opt/bimba-setu
EXPOSE 8090 8443
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8090"]
ENTRYPOINT ["java", "-jar", "/opt/bimba-setu/bimba-setu.jar"]
