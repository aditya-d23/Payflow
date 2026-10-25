# syntax=docker/dockerfile:1

# ---- build -----------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source edits don't re-download.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/payment-service-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --destination /app

# ---- runtime ---------------------------------------------------------------
FROM eclipse-temurin:25-jre
RUN groupadd --system payflow && useradd --system --gid payflow --no-create-home payflow
WORKDIR /app
COPY --from=build --chown=payflow:payflow /app ./
USER payflow

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
