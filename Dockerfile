FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
ARG RAILWAY_GIT_COMMIT_SHA
RUN if [ -n "$RAILWAY_GIT_COMMIT_SHA" ]; then \
    sed -i "s/sentryRelease: ''/sentryRelease: '$RAILWAY_GIT_COMMIT_SHA'/" \
    src/main/frontend/src/environments/environment.ts; \
  fi
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS="-Xmx384m -Xms128m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
