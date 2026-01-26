# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
# ensure fat/executable jar is built
RUN --mount=type=cache,target=/root/.m2 \
    mvn -T 1C -DskipTests -pl obsinity-reference-service -am clean package

FROM eclipse-temurin:21-jre
WORKDIR /opt/app
# ✅ copy built jars and select the executable jar (exclude -plain)
COPY --from=build /app/obsinity-reference-service/target/*.jar /opt/app/
RUN set -e; \
    jar=$(ls /opt/app/*.jar | grep -v -- '-plain' | head -n 1); \
    if [ -z "$jar" ]; then echo "No executable jar found" >&2; exit 1; fi; \
    mv "$jar" /opt/app/app.jar; \
    find /opt/app -maxdepth 1 -name "*.jar" ! -name "app.jar" -delete
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/opt/app/app.jar"]
