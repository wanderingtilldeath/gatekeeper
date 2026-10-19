# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8888
# No actuator dependency on this service, so healthcheck hits a real config
# endpoint (Config Server serves /{application}/{profile} out of the box).
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -f http://localhost:8888/gateway/default || exit 1
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
