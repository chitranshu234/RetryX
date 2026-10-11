# ============================================================================
#  Retryx reference receiver
# ============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S receiver && adduser -S receiver -G receiver

COPY --from=build /build/target/*.jar app.jar
RUN chown receiver:receiver /app/app.jar
USER receiver

EXPOSE 9090

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
