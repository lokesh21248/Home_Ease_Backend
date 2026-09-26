# Multi-stage optimized Dockerfile for Spring Boot
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the pre-built jar file
COPY target/homeease-backend-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
