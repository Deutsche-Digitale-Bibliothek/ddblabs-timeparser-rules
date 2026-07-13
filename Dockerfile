# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
COPY rules.csv tests.csv ./
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:25-jre

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app /data \
    && chown -R app:app /app /data

WORKDIR /app

COPY --from=build --chown=app:app /workspace/target/ddblabs-timeparser-rules-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chown=app:app rules.csv tests.csv ./

ENV TIMEPARSER_SERVER_PORT=8080
ENV TIMEPARSER_URL_PREFIX=/app/timeparser-rules
ENV TIMEPARSER_DATABASE_PATH=/data/timeparser-rules.sqlite
ENV TIMEPARSER_LOG_LEVEL=INFO

EXPOSE 8080

USER app:app

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
