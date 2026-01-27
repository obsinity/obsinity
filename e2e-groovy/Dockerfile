FROM eclipse-temurin:21-jre
WORKDIR /opt/app
COPY build-context/app.jar /opt/app/app.jar
EXPOSE 8086
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/opt/app/app.jar"]
