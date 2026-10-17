# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# The Maven cache survives between builds on the same machine; tests run in CI, not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar bot/target/race-radio-bot-*.jar extract --destination /out \
 && mv /out/race-radio-bot-*.jar /out/race-radio.jar

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-noble

# The Claude Code version this service was measured against. The command line is how the coach
# reaches Claude through a subscription; its flags and its output were checked on this version.
ARG CLAUDE_VERSION=2.1.302

ENV RACE_RADIO_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=60" \
    TZ=Europe/Paris

# DejaVu: Batik needs an installed font, or the charts come out without a single label.
RUN apt-get update && apt-get install -y --no-install-recommends \
      fonts-dejavu-core ca-certificates curl tini \
 && rm -rf /var/lib/apt/lists/*

# Uid 1000 to match the first user on most hosts, so a bind-mounted data folder stays writable.
RUN (id -u 1000 >/dev/null 2>&1 || useradd -u 1000 -M rr) \
 && mkdir -p /data /home/rr \
 && chown 1000:1000 /data /home/rr

COPY --from=build /out /app
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENV HOME=/home/rr \
    PATH=/home/rr/.local/bin:$PATH
USER 1000
# The official installer, at a pinned version, for this user only.
RUN curl -fsSL https://claude.ai/install.sh | bash -s -- "$CLAUDE_VERSION" \
 && claude --version

EXPOSE 8790
HEALTHCHECK --interval=60s --timeout=5s --start-period=60s \
  CMD curl -fsS http://127.0.0.1:8790/actuator/health >/dev/null || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
