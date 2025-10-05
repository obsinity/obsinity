# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY . ./

RUN --mount=type=cache,target=/root/.m2 \
    mvn -T 1C -pl obsinity-reference-client-spring -am -DskipTests clean package


FROM eclipse-temurin:17-jre
WORKDIR /opt/app

COPY --from=build /workspace/obsinity-reference-client-spring/target/obsinity-reference-client-spring-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
