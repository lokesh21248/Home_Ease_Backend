FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/homeease-notification-service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
