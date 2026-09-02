# Base images are pinned by digest (multi-arch manifest list) so builds cannot drift when tags move.
FROM maven:3.9-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 AS build
WORKDIR /build
COPY . .
RUN mvn -q -pl gateway-demo -am package -DskipTests

FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112
RUN useradd --system --uid 10001 gateway
WORKDIR /app
COPY --from=build /build/gateway-demo/target/gateway-demo-0.1.0-SNAPSHOT.jar app.jar
USER gateway
EXPOSE 8080
# TCP probe on the listening port (the JRE image ships no curl/wget). GATEWAY_PORT is set by compose;
# a CLI port argument would bypass this check, which is an accepted demo trade-off.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${GATEWAY_PORT:-8080}'
ENTRYPOINT ["java", "-jar", "app.jar"]
