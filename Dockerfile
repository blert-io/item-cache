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
WORKDIR /app
COPY --from=build /build/build/libs/*-all.jar /app/item-cache-dumper.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENV OUTPUT_DIR=/resources \
    CACHE_ROOT=/work/cache \
    JAR=/app/item-cache-dumper.jar

VOLUME ["/work/cache"]

ENTRYPOINT ["/app/entrypoint.sh"]
