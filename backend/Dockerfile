# syntax=docker/dockerfile:1
# Image du serveur CoopHabitat (Spring Boot). Construction en deux étapes : compilation puis exécution
# sur un JRE minimal, sous un utilisateur non privilégié.
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# Les tests sont exécutés par l'intégration continue (ils ont besoin de Docker pour Testcontainers).
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp target/coophabitat-*.jar /src/app.jar

FROM ${RUNTIME_IMAGE}
RUN addgroup -S coop && adduser -S -G coop -h /app coop \
    && mkdir -p /data/documents && chown -R coop:coop /data
WORKDIR /app
COPY --from=build --chown=coop:coop /src/app.jar app.jar
USER coop
ENV TZ=Africa/Dakar \
    COOPHABITAT_STORAGE_PATH=/data/documents \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME /data/documents
EXPOSE 8080
# « readiness » ne passe à UP qu'après l'exécution des tâches de démarrage (migrations, données de démonstration).
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
