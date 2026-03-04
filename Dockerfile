# ---- build stage ----
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven
COPY pom.xml .
# Pre-fetch dependencies so they are cached in a separate layer
RUN mvn -q dependency:go-offline -B
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/taxonomy-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
