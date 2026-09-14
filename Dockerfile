# Operator image
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 cfpo && adduser -S -u 10001 -G cfpo cfpo
COPY --from=build /src/target/cfpo.jar /opt/cfpo/cfpo.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/cfpo/cfpo.jar"]
