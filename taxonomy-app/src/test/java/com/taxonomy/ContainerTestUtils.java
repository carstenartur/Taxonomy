package com.taxonomy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Shared utilities for container-based integration tests.
 */
final class ContainerTestUtils {

    private ContainerTestUtils() {
    }

    /**
     * Dynamically resolves the Spring Boot fat JAR in the {@code target/} directory.
     * This avoids hardcoding the version, which changes during release builds
     * (e.g. {@code taxonomy-1.0.0-SNAPSHOT.jar} vs {@code taxonomy-1.0.0.jar}).
     */
    static Path findApplicationJar() {
        try (var stream = Files.list(Path.of("target"))) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("taxonomy-.*\\.jar"))
                    .filter(p -> !p.getFileName().toString().contains("sources"))
                    .filter(p -> !p.getFileName().toString().contains("javadoc"))
                    .filter(p -> p.toFile().length() > 1_000_000) // Spring Boot fat JAR should be large
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No taxonomy-*.jar found in target/. Run 'mvn package -DskipTests' first."));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan target/ directory for application JAR", e);
        }
    }

    /**
     * Creates a pre-configured application container on the given Docker network.
     * The container is built from the Spring Boot fat JAR and exposes port 8080.
     * Callers can add additional env vars (e.g. database configuration) before
     * the container is started.
     *
     * @param network the Docker network to attach the container to
     * @return a configured but <em>not yet started</em> {@link GenericContainer}
     */
    static GenericContainer<?> appContainer(Network network) {
        return new GenericContainer<>(
                new ImageFromDockerfile()
                        .withFileFromPath("app.jar", findApplicationJar())
                        .withDockerfileFromBuilder(builder -> builder
                                .from("eclipse-temurin:17-jre-alpine")
                                .workDir("/app")
                                .copy("app.jar", "app.jar")
                                .expose(8080)
                                .entryPoint("java", "-jar", "app.jar")
                                .build()))
                .withNetwork(network)
                .withNetworkAliases("app")
                .withExposedPorts(8080)
                .withStartupTimeout(Duration.ofSeconds(180))
                .waitingFor(Wait.forHttp("/api/ai-status")
                        .forStatusCode(200)
                        .forPort(8080));
    }
}
