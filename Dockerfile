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
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
