# Stage 1: Build
ARG ECR_REPO
FROM maven:3.8.5-openjdk-11 as build
WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests
# Stage 2: Production
FROM tomcat:9.0.83-jdk11-temurin-jammy

ENV JAVA_OPTS="-Xmx4096m"
RUN apt-get update && apt-get install -y unzip  

RUN rm -rf /usr/local/tomcat/webapps/ROOT
COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war
