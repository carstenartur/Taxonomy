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
     * (e.g. {@code taxonomy-app-1.0.0-SNAPSHOT.jar} vs {@code taxonomy-app-1.0.0.jar}).
     * <p>
     * When run via Maven Failsafe the working directory is {@code taxonomy-app/},
     * so {@code target/} resolves correctly. When launched from an IDE the working
     * directory is typically the repository root, so we also try
     * {@code taxonomy-app/target/} as a fallback.
     */
    static Path findApplicationJar() {
        // When run via Maven Failsafe the CWD is taxonomy-app/, so target/ works.
        // When launched from an IDE the CWD is the repo root — but the repo root
        // also has a target/ directory (build metadata, no JARs). We therefore
        // search *both* candidate directories for the fat JAR.
        Path moduleTarget = Path.of("target");
        Path repoRootTarget = Path.of("taxonomy-app", "target");
        for (Path targetDir : new Path[]{moduleTarget, repoRootTarget}) {
            if (!Files.isDirectory(targetDir)) {
                continue;
            }
            try (var stream = Files.list(targetDir)) {
                var jar = stream
                        .filter(p -> p.getFileName().toString().matches("taxonomy-.*\\.jar"))
                        .filter(p -> !p.getFileName().toString().contains("sources"))
                        .filter(p -> !p.getFileName().toString().contains("javadoc"))
                        .filter(p -> p.toFile().length() > 1_000_000) // Spring Boot fat JAR should be large
                        .findFirst();
                if (jar.isPresent()) {
                    return jar.get();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to scan " + targetDir + "/ directory for application JAR", e);
            }
        }
        throw new IllegalStateException(
                "No taxonomy-*.jar found in " + moduleTarget + "/ or " + repoRootTarget
                        + "/. Run 'mvn package -DskipTests' first.");
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
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .forPort(8080));
    }
}
