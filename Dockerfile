# syntax=docker/dockerfile:1

# Build: Maven + JDK 25. O .m2 fica em cache entre builds.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# Runtime: só o JRE, com um utilizador sem privilégios.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar

USER app
EXPOSE 8080

# O /api/v1/ping é público e não precisa de tenant (sem subdomínio devolve "nenhum").
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/api/v1/ping || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
