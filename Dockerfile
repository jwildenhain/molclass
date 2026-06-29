FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
# We need to grant execute permissions in case gradlew lost it
RUN chmod +x gradlew
RUN ./gradlew :spring_boot_predictor:bootJar -x test

FROM eclipse-temurin:17-jre-alpine AS runner
WORKDIR /app
COPY --from=builder /app/spring_boot_predictor/build/libs/*.jar app.jar
# The backend relies on models stored in 'models' or 'models_modern'
COPY --from=builder /app/models /app/models
COPY --from=builder /app/models_modern /app/models_modern
EXPOSE 8080
# Set legacyWeka property to false (use models_modern)
CMD ["java", "-jar", "app.jar", "--spring.datasource.url=${DB_URL}", "--spring.datasource.username=${DB_USER}", "--spring.datasource.password=${DB_PASS}"]
