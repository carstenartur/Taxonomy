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
     * Resolves the Spring Boot fat JAR in the {@code target/} directory.
     * <p>
     * When run via Maven Failsafe, the {@code project.build.finalName} system
     * property is set (e.g. {@code taxonomy-app-1.1.3-SNAPSHOT}), so the JAR
     * name is constructed deterministically as {@code <finalName>.jar}.
     * <p>
     * When launched from an IDE (system property absent), a regex scan of
     * {@code target/} and {@code taxonomy-app/target/} is used as a fallback,
     * excluding {@code -original}, {@code -sources}, and {@code -javadoc} JARs
     * and preferring the largest remaining match.
     */
    static Path findApplicationJar() {
        Path moduleTarget = Path.of("target");
        Path repoRootTarget = Path.of("taxonomy-app", "target");

        // --- Deterministic path via Maven system property (set by Failsafe) ---
        String finalName = System.getProperty("project.build.finalName");
        if (finalName != null) {
            String jarName = finalName + ".jar";
            for (Path targetDir : new Path[]{moduleTarget, repoRootTarget}) {
                Path candidate = targetDir.resolve(jarName);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "Expected JAR '" + jarName + "' not found in " + moduleTarget + "/ or "
                            + repoRootTarget + "/. Run 'mvn package -DskipTests' first.");
        }

        // --- Fallback for IDE runs: scan target/ directories ---
        for (Path targetDir : new Path[]{moduleTarget, repoRootTarget}) {
            if (!Files.isDirectory(targetDir)) {
                continue;
            }
            try (var stream = Files.list(targetDir)) {
                var jar = stream
                        .filter(p -> p.getFileName().toString().matches("taxonomy-app-.*\\.jar"))
                        .filter(p -> !p.getFileName().toString().contains("original"))
                        .filter(p -> !p.getFileName().toString().contains("sources"))
                        .filter(p -> !p.getFileName().toString().contains("javadoc"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().length()));
                if (jar.isPresent()) {
                    return jar.get();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to scan " + targetDir + "/ directory for application JAR", e);
            }
        }
        throw new IllegalStateException(
                "No taxonomy-app-*.jar found in " + moduleTarget + "/ or " + repoRootTarget
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
                                .from("eclipse-temurin:17-jre")
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
