# Multi-stage build for either service module — used by the `app` profile in
# docker-compose.yml:
#   docker build --build-arg MODULE=sclera-procedure-service -t sclera/procedure-service .

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE=sclera-procedure-service
WORKDIR /workspace

# sclera-common ships as a bare jar — install it into the build container's local
# repo. generatePom is required: the pom embedded in the jar declares an
# unpublished parent (sclera-control-plane) and breaks dependency resolution.
COPY jars/ jars/
RUN mvn -q install:install-file \
      -Dfile=jars/sclera-common-0.1.0-SNAPSHOT.jar \
      -DgroupId=com.sclera -DartifactId=sclera-common \
      -Dversion=0.1.0-SNAPSHOT -Dpackaging=jar -DgeneratePom=true

COPY pom.xml .
COPY sclera-procedure-service/ sclera-procedure-service/
COPY sclera-inspection-service/ sclera-inspection-service/
RUN mvn -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE=sclera-procedure-service
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
