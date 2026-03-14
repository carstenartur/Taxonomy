package com.taxonomy;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

/**
 * Integration tests for the Diagnostics endpoint running inside a real Docker
 * container. Uses the pre-built application JAR packaged into an
 * eclipse-temurin:17-jre-alpine container. Validates that the application
 * starts correctly and the diagnostics and other API endpoints behave as
 * expected in a production-like setup.
 * <p>
 * This is the default HSQLDB variant — no external database container is needed.
 */
@Testcontainers
class DiagnosticsContainerIT extends AbstractDatabaseContainerIT {

    @Container
    static GenericContainer<?> app = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("app.jar", ContainerTestUtils.findApplicationJar())
                    .withDockerfileFromBuilder(builder -> builder
                            .from("eclipse-temurin:17-jre-alpine")
                            .workDir("/app")
                            .copy("app.jar", "app.jar")
                            .expose(8080)
                            .entryPoint("java", "-jar", "app.jar")
                            .build()))
            .withExposedPorts(8080)
            .withStartupTimeout(Duration.ofSeconds(120))
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forStatusCode(200)
                    .forPort(8080));

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }
}
