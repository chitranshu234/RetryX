# ============================================================================
#  Retryx API — multi-stage build
# ============================================================================

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies are resolved in their own layer, keyed on the pom alone. Copying
# the whole source first would invalidate this layer on every code change and
# re-download the entire dependency tree for a one-line edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests need Docker (Testcontainers), which is not available inside the build.
# They run in CI and locally via `mvn verify`, not here.
RUN mvn -B -q clean package -DskipTests

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# A non-root user, because a webhook sender makes outbound requests to
# user-supplied URLs and is therefore exactly the kind of process you do not
# want running as root when something goes wrong.
RUN addgroup -S retryx && adduser -S retryx -G retryx

COPY --from=build /build/target/*.jar app.jar
RUN chown retryx:retryx /app/app.jar
USER retryx

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the container's memory limit is set
# at deploy time, and a hardcoded heap either wastes the limit or gets the
# process OOM-killed by the kernel with no Java stack trace to show for it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
