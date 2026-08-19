FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew --no-daemon :spring_boot_predictor:bootJar installDist -x test

FROM eclipse-temurin:17-jre-alpine AS predictor
RUN addgroup -S molclass && adduser -S -G molclass molclass
WORKDIR /app
COPY --from=builder --chown=molclass:molclass /app/spring_boot_predictor/build/libs/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER molclass
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:17-jre-alpine AS sdf-worker
RUN addgroup -S molclass && adduser -S -G molclass molclass
WORKDIR /app
COPY --from=builder --chown=molclass:molclass /app/build/install/molclass/lib ./lib
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx1g -XX:+ExitOnOutOfMemoryError"
USER molclass
ENTRYPOINT ["java", "-cp", "/app/lib/*", "molclass.importer.V3SdfWorker"]

FROM eclipse-temurin:17-jre-alpine AS model-worker
RUN addgroup -S molclass && adduser -S -G molclass molclass
WORKDIR /app
COPY --from=builder --chown=molclass:molclass /app/build/install/molclass/lib ./lib
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx12g -XX:+ExitOnOutOfMemoryError"
USER molclass
ENTRYPOINT ["java", "-cp", "/app/lib/*", "molclass.models.V3ModelPipelineWorker"]
