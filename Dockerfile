# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradle /app/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /app/

RUN chmod +x /app/gradlew

# Pre-fetch dependencies for better layer caching.
RUN /app/gradlew --no-daemon dependencies || true

COPY src /app/src

RUN /app/gradlew --no-daemon bootJar


FROM eclipse-temurin:21-jre

WORKDIR /app

ENV JAVA_OPTS=""

COPY --from=build /app/build/libs/*.jar /app/secrux-server.jar

EXPOSE 8080 5155

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/secrux-server.jar"]

