# Hugging Face Space: builds BIMBA Setu from the public GitHub repo (React console -> Spring Boot jar -> JRE image).
# The Space only holds this file and README.md; change REPO_REF to deploy another branch or tag.
FROM mirror.gcr.io/library/node:22-alpine AS ui
ARG REPO_URL=https://github.com/SaiNikhil206/bimba_IT_OT.git
ARG REPO_REF=release/dev
RUN apk add --no-cache git \
 && git clone --depth 1 --branch "$REPO_REF" "$REPO_URL" /repo
WORKDIR /repo/bimba-setu/ui
RUN npm ci --no-audit --no-fund && npm run build

FROM mirror.gcr.io/library/gradle:9.2-jdk21 AS build
WORKDIR /src
COPY --from=ui /repo/bimba-setu/settings.gradle /repo/bimba-setu/build.gradle ./
COPY --from=ui /repo/bimba-setu/collect-lib ./collect-lib
COPY --from=ui /repo/bimba-setu/edge/build.gradle ./edge/build.gradle
COPY --from=ui /repo/bimba-setu/agent/build.gradle ./agent/build.gradle
COPY --from=ui /repo/bimba-setu/src ./src
COPY --from=ui /repo/bimba-setu/ui/dist ./src/main/resources/static
RUN gradle bootJar --no-daemon -q

FROM mirror.gcr.io/library/eclipse-temurin:21-jre
# Spaces run the container as uid 1000; /config and /data must be writable by it
RUN useradd -m -u 1000 user \
 && mkdir -p /config /data /installers /opt/bimba-setu \
 && echo '{"name":"BIMBA","tagType":"Folder","tags":[]}' > /config/demo-tags.json
# Site Agent installers offered in the console, from the GitHub release
ARG INSTALLERS_URL=https://github.com/SaiNikhil206/bimba_IT_OT/releases/download/site-agent-installers
RUN for f in BIMBA-Site-Agent-Setup.exe BIMBA-Site-Agent.msi bimba-site-agent_all.deb bimba-site-agent-linux.tar.gz release.json release-linux.json; do \
      curl -fsSL --retry 3 -o "/installers/$f" "$INSTALLERS_URL/$f" || { echo "installer $f not available"; rm -f "/installers/$f"; }; \
    done \
 && chown -R 1000:1000 /config /data /installers /opt/bimba-setu
COPY --from=build --chown=1000:1000 /src/build/libs/bimba-setu.jar /opt/bimba-setu/bimba-setu.jar
ENV BIMBA_CONFIG_DIR=/config BIMBA_DATA_DIR=/data TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
USER user
WORKDIR /opt/bimba-setu
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/opt/bimba-setu/bimba-setu.jar"]
