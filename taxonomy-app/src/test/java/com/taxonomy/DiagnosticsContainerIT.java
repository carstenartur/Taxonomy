package com.taxonomy;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the Diagnostics endpoint running inside a real Docker
 * container. Uses the pre-built application JAR packaged into an
 * eclipse-temurin:17-jre container. Validates that the application
 * starts correctly and the diagnostics and other API endpoints behave as
 * expected in a production-like setup.
 * <p>
 * This is the default HSQLDB variant — no external database container is needed.
 */
@Testcontainers
class DiagnosticsContainerIT extends AbstractDatabaseContainerIT {

    @Container
    static GenericContainer<?> app = ContainerTestUtils.appContainer();

    @Override
    protected GenericContainer<?> getAppContainer() {
        return app;
    }
}
