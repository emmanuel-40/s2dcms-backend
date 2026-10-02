# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Pin the container clock to UTC. Timestamps are written with LocalDateTime.now(); without this the
# value depends on the host's TZ, and it is then mis-serialised as local time by Jackson (see
# JacksonUtcConfig). Fixing the clock here makes the stored value UTC everywhere, deterministically.
ENV TZ=UTC
#
# Container sizing. Render's free tier provides 512MB RAM and a 0.1 shared CPU.
#
# The JVM's default container heuristic caps the heap at 25% of RAM, i.e. 128MB. Hibernate's
# entity cache plus Tomcat's buffers exhaust that quickly, so the JVM then spends much of its time
# in GC rather than serving requests - which shows up as identical requests varying three-fold in
# latency. Raising the heap to 75% of RAM (384MB) leaves headroom for metaspace and direct buffers
# while giving the application a heap that is not thrashing.
#
# UseSerialGC suits a single small CPU: it uses markedly less CPU per collection than the
# parallel collector, which is the scarce resource here rather than pause duration.
#
# Unverified locally (it only takes effect in a container build) - confirm in the Render logs that
# startup and p95 latency improve after the next deploy.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-XX:MaxRAMPercentage=75.0", "-XX:+UseSerialGC", "-jar", "app.jar"]
