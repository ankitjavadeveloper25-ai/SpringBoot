# ==============================================================================
# WINTER ARC PRO — SERVER DOCKERFILE (When Root Directory = server)
# Multi-stage Docker build for Spring Boot Backend on Java 21
# ==============================================================================

# Stage 1: Build JAR using Maven
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Copy POM and download dependencies for layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source code and build production executable JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as dedicated non-root user
RUN addgroup -S winterarc && adduser -S winterarc -G winterarc

COPY --from=builder /workspace/target/winter-arc-server-*.jar /app/app.jar

RUN chown -R winterarc:winterarc /app

USER winterarc

EXPOSE 8080

ENV PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:${PORT}/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT} -Dspring.profiles.active=prod -jar /app/app.jar"]
