# Stage 1: Build
ARG ECR_REPO
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests
# Stage 2: Production
FROM tomcat:10.1.31-jdk17-temurin-jammy AS fnl_base_image

ENV JAVA_OPTS="-Xmx4096m"
RUN apt-get update && apt-get install -y unzip

RUN rm -rf /usr/local/tomcat/webapps/ROOT
COPY --from=build /usr/src/app/target/ICDC-14.10.0.war /usr/local/tomcat/webapps/ROOT.war
