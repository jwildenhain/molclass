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
# installDist names its output dir after settings.gradle's rootProject.name (MolClass), not
# the lowercase artifact/entrypoint naming used elsewhere in this file.
COPY --from=builder --chown=molclass:molclass /app/build/install/MolClass/lib ./lib
# Mode 1777, not chown: this shares the upload_data volume with the api service,
# which comes from a wholly different (Debian) image whose molclass user gets a
# different uid/gid than this Alpine one. See the equivalent line in
# html/molclass/api/Dockerfile.
RUN mkdir -p /var/lib/molclass/uploads && chmod 1777 /var/lib/molclass/uploads
# MaxRAMPercentage, not a fixed -Xmx: this must stay inside whatever mem_limit
# docker-compose.yml sets for the container, whatever that ends up being on a
# given host, same as the predictor stage above already does correctly.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER molclass
ENTRYPOINT ["java", "-cp", "/app/lib/*", "molclass.importer.V3SdfWorker"]

FROM eclipse-temurin:17-jre-alpine AS model-worker
RUN addgroup -S molclass && adduser -S -G molclass molclass
WORKDIR /app
COPY --from=builder --chown=molclass:molclass /app/build/install/MolClass/lib ./lib
# A fixed -Xmx12g here was ignoring docker-compose.yml's own mem_limit entirely --
# on a host where that limit is set well below 12g, the JVM grows toward 12g
# under load and the container's cgroup OOM-kills it (exit 137) before a build
# can finish. MaxRAMPercentage keeps the heap inside whatever mem_limit is
# actually configured, same fix as the sdf-worker and predictor stages.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER molclass
ENTRYPOINT ["java", "-cp", "/app/lib/*", "molclass.models.V3ModelPipelineWorker"]
