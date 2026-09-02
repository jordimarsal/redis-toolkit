FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY . .
RUN mvn -q -pl gateway-demo -am package -DskipTests

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 gateway
WORKDIR /app
COPY --from=build /build/gateway-demo/target/gateway-demo-0.1.0-SNAPSHOT.jar app.jar
USER gateway
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
