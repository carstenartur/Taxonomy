package com.taxonomy.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads committed application resources for repository source-content contracts.
 * This is deliberately not a packaged-resource check: the executable application
 * JAR keeps its resources below BOOT-INF/classes, outside a normal dependency's
 * classpath. Application and container tests continue to verify packaged delivery.
 */
public final class RepositoryResources {
    private RepositoryResources() { }

    public static String applicationResource(String resource) throws IOException {
        return applicationResource(Path.of(System.getProperty("user.dir")), resource);
    }

    static String applicationResource(Path workingDirectory, String resource) throws IOException {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("An application resource path is required");
        }
        Path relative = Path.of(resource.startsWith("/") ? resource.substring(1) : resource).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
            throw new IllegalArgumentException("Path is outside application resources: " + resource);
        }
        Path root = workingDirectory.toAbsolutePath().normalize();
        while (root != null) {
            Path resources = root.resolve("taxonomy-app/src/main/resources");
            if (Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))
                    && Files.isDirectory(resources)) {
                return Files.readString(resources.resolve(relative), StandardCharsets.UTF_8);
            }
            root = root.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository from " + workingDirectory);
    }
}
