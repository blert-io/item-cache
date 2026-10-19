# Build stage
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

# Copy the gradle wrapper first so its distribution download is cached across
# source-only rebuilds.
COPY java/gradlew java/gradlew.bat ./
COPY java/gradle ./gradle
COPY java/settings.gradle.kts java/build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon help >/dev/null
COPY java/src ./src
RUN ./gradlew --no-daemon --console=plain shadowJar

# Run stage
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl jq unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG S5CMD_VERSION=2.3.0
ARG S5CMD_SHA256=de0fdbfa3aceae55e069ba81a0fc17b2026567637603734a387b2fca06c299b4
RUN curl -fsSL -o /tmp/s5cmd.tar.gz \
        "https://github.com/peak/s5cmd/releases/download/v${S5CMD_VERSION}/s5cmd_${S5CMD_VERSION}_Linux-64bit.tar.gz" \
    && echo "${S5CMD_SHA256}  /tmp/s5cmd.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/s5cmd.tar.gz -C /usr/local/bin s5cmd \
    && rm /tmp/s5cmd.tar.gz

WORKDIR /app
COPY --from=build /build/build/libs/*-all.jar /app/item-cache-dumper.jar
COPY entrypoint.sh upload-sprites.sh /app/
RUN chmod +x /app/entrypoint.sh /app/upload-sprites.sh

ENV OUTPUT_DIR=/resources \
    CACHE_ROOT=/work/cache \
    JAR=/app/item-cache-dumper.jar

VOLUME ["/work/cache"]

ENTRYPOINT ["/app/entrypoint.sh"]
