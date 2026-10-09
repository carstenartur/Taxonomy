package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Full distribution, not only the executable JAR: every owned class and resource appears exactly once. */
class FinalRuntimeModulesPackagingIT {
    private static final Set<String> OPTIONAL = Set.of("templates", "interop", "architecture", "analysis", "portfolio", "reporting");
    private static final List<String> MODULES = List.of("domain", "dsl", "export", "extension-api", "extension-runtime",
            "workspace", "knowledge", "templates-api", "templates", "interop", "architecture", "analysis", "portfolio", "reporting-api", "reporting");
    @Test void allLibrariesAndOwnedResourcesAreDistributedExactlyOnce() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Map<String, String> owners = new HashMap<>();
        for (String context : MODULES) {
            String module = "taxonomy-" + context; Path output = root.resolve(module + "/target/classes");
            assertThat(output).as("compiled library %s", module).isDirectory();
            try (var files = Files.walk(output)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String name = output.relativize(file).toString().replace('\\', '/');
                    assertThat(owners.put(name, context)).as("unique compiled owner of %s", name).isNull();
                }
            }
        }
        assertThat(owners).isNotEmpty();
        Map<String, Path> resources = new HashMap<>(); Map<String, String> resourceOwners = new HashMap<>();
        Path analysis = root.resolve("taxonomy-analysis/src/main/resources");
        for (String directory : List.of("prompts", "mock-scores")) {
            try (var files = Files.walk(analysis.resolve(directory))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = analysis.relativize(file).toString().replace('\\', '/');
                    resources.put(name, file); resourceOwners.put(name, "analysis");
                }
            }
        }
        for (var resource : Map.of("ai-automation-defaults.properties", "portfolio",
                "document-templates/decision-rationale-report.dotx", "reporting").entrySet()) {
            resources.put(resource.getKey(), root.resolve("taxonomy-" + resource.getValue() + "/src/main/resources/" + resource.getKey()));
            resourceOwners.put(resource.getKey(), resource.getValue());
        }
        Set<String> foundClasses = new HashSet<>(), foundResources = new HashSet<>();
        var archives = DistributionArchives.inspect(root, (archive, path, bytes) -> {
            if (owners.containsKey(path)) {
                String owner = owners.get(path);
                DistributionArchives.assertOwner(archive, "taxonomy-" + owner, OPTIONAL.contains(owner));
                assertThat(foundClasses.add(path)).as("unique delivered class %s", path).isTrue();
            }
            if (resources.containsKey(path)) {
                DistributionArchives.assertOwner(archive, "taxonomy-" + resourceOwners.get(path), true);
                assertThat(foundResources.add(path)).as("unique delivered resource %s", path).isTrue();
                assertThat(bytes.readAllBytes()).isEqualTo(Files.readAllBytes(resources.get(path)));
            }
        });
        for (String module : MODULES) {
            String location = OPTIONAL.contains(module) ? "features/" : "BOOT-INF/lib/";
            assertThat(archives.stream().filter(n -> n.matches(location + "taxonomy-" + module + "-[0-9].*\\.jar")))
                    .as("one delivered %s library", module).hasSize(1);
        }
        assertThat(archives.stream().filter(n -> n.startsWith("features/"))).hasSize(OPTIONAL.size());
        assertThat(foundClasses).containsExactlyInAnyOrderElementsOf(owners.keySet());
        assertThat(foundResources).containsExactlyInAnyOrderElementsOf(resources.keySet());
    }
}
