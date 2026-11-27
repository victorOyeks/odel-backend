# Build the jar first (mvn -DskipTests package); this image only runs it.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 odel
WORKDIR /app
COPY target/odel-api.jar app.jar
USER odel
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
