# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
# ensure fat/executable jar is built
RUN --mount=type=cache,target=/root/.m2 \
    mvn -T 1C -DskipTests -pl obsinity-reference-service -am clean package

FROM eclipse-temurin:21-jre
WORKDIR /opt/app
# ✅ deterministically copy the executable jar (exclude -plain)
COPY --from=build /app/obsinity-reference-service/target/*-SNAPSHOT.jar /opt/app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/opt/app/app.jar"]
