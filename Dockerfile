# Stage 1: Build
ARG ECR_REPO
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests
# Stage 2: Production
FROM tomcat:11.0.21-jdk17-temurin-noble AS fnl_base_image

ENV JAVA_OPTS="-Xmx4096m"
RUN apt-get update && apt-get install -y unzip

RUN rm -rf /usr/local/tomcat/webapps/ROOT \
    && rm -rf /usr/local/tomcat/webapps.dist
COPY --from=build /usr/src/app/target/ICDC-14.10.0.war /usr/local/tomcat/webapps/ROOT.war
