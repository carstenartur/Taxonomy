package com.taxonomy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
