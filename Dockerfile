# Etapa 1: compilación con Maven y JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Las pruebas corren fuera de la imagen (mvn verify)
RUN mvn -B -q -DskipTests package

# Etapa 2: imagen liviana solo con JRE 21
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/target/buildshield-inventory-service-*.jar app.jar
USER app
EXPOSE 8083
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
