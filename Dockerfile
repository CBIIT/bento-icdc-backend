# Stage 1: Build
ARG ECR_REPO
FROM maven:3.8.5-openjdk-11 AS build
WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests
# Stage 2: Production
#FROM tomcat:10.1.17-jdk17 AS fnl_base_image
FROM 9.0.102-jdk8-corretto-al2 AS fnl_base_image

ENV JAVA_OPTS="-Xmx4096m"
RUN yum update -y && yum install -y unzip  

RUN rm -rf /usr/local/tomcat/webapps/ROOT
COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war
