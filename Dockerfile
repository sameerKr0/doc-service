# syntax=docker/dockerfile:1

# ---- build stage: compile and run unit tests are done in CI; skip them here for a fast image build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package

# ---- runtime stage: JRE only, non-root
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home appuser
WORKDIR /app
COPY --from=build /workspace/target/app.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
