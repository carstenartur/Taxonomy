package com.nato.taxonomy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared utility for container-based integration tests.
 * Dynamically resolves the Spring Boot fat JAR in the target/ directory
 * so tests work regardless of the project version (SNAPSHOT or release).
 */
final class ContainerTestSupport {

    private ContainerTestSupport() {
        // utility class
    }

    /**
     * Finds the Spring Boot repackaged JAR in target/.
     * Matches {@code taxonomy-*.jar} excluding sources/javadoc JARs and
     * the original (non-repackaged) thin JAR.
     *
     * @return Path to the application fat JAR
     * @throws IllegalStateException if no matching JAR is found
     */
    static Path findApplicationJar() {
        Path targetDir = Path.of("target");
        try (var stream = Files.list(targetDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("taxonomy-.*\\.jar"))
                    .filter(p -> !p.getFileName().toString().contains("sources"))
                    .filter(p -> !p.getFileName().toString().contains("javadoc"))
                    .filter(p -> !p.getFileName().toString().endsWith(".jar.original"))
                    // Spring Boot fat JAR should be at least 1 MB
                    .filter(p -> p.toFile().length() > 1_000_000)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No taxonomy-*.jar found in " + targetDir.toAbsolutePath()
                                    + ". Run 'mvn package' first."));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list files in " + targetDir, e);
        }
    }
}