FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q package -DskipTests && cp target/*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /app
# Don't run as root
RUN useradd --system --uid 10001 app
# Extracted layout (app.jar + lib/) is required for the CDS archive below
COPY --from=build /app/extracted/ ./

# CDS training run: start the Spring context once at build time and record the loaded,
# parsed classes into app.jsa. Startup then maps that archive instead of redoing the work.
# Needs no DB: the context exits right after refresh, and Flyway (the only startup DB user)
# is switched off for this run only.
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -Dspring.flyway.enabled=false \
        -jar app.jar > /dev/null
USER app

# Tuned for a small container (measured at 0.1 CPU / 512MB, the Render free tier):
#  - CDS archive + C1-only JIT (TieredStopAtLevel=1): cold start 246s -> 66s, and throughput
#    doubled, because the C2 compiler's threads were competing with requests for the CPU
#  - heap capped at 60% of the container limit, leaving room for metaspace, stacks, buffers
#  - 512k thread stacks, SerialGC: lowest overhead on a fraction of a CPU
ENV JAVA_OPTS="-XX:SharedArchiveFile=app.jsa -XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=60 -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# exec => java is PID 1 and receives SIGTERM directly, so Spring's graceful shutdown
# finishes in-flight requests on redeploy instead of being killed mid-transaction.
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
