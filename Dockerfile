ARG ECR_REPO
FROM maven:3.8.5-openjdk-17 as build
WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests

# Production stage
FROM tomcat:9.0.80-jdk11-temurin-jammy
LABEL MAINTAINER="icdc devops team"
RUN JAVA_OPTIONS="--max-old-space-size=4096"
RUN rm -rf /usr/local/tomcat/webapps/ROOT
COPY --from=build /usr/src/app/target/Bento-0.0.1.war /usr/local/tomcat/webapps/ROOT.war
