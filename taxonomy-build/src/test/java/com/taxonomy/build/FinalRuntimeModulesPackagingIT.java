package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.assertThat;

/** Ensures the one executable artifact really contains every extracted context and its assets. */
class FinalRuntimeModulesPackagingIT {
    private static final List<String> CONTEXTS = List.of(
            "workspace", "knowledge", "templates", "interop", "architecture", "analysis", "portfolio");

    @Test
    void allSevenLibrariesAndOwnedResourcesArePackagedExactlyOnce() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        Map<String, String> owners = new HashMap<>();
        for (String context : CONTEXTS) {
            String module = "taxonomy-" + context;
            Path output = root.resolve(module + "/target/classes");
            assertThat(output).as("compiled feature library %s", module).isDirectory();
            try (var paths = Files.walk(output)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String name = output.relativize(path).toString().replace('\\', '/');
                    assertThat(owners.put(name, module)).as("unique compiled owner of %s", name).isNull();
                }
            }
        }
        assertThat(owners).isNotEmpty();
        Map<String, Path> resources = new HashMap<>();
        Path analysis = root.resolve("taxonomy-analysis/src/main/resources");
        for (String directory : List.of("prompts", "mock-scores")) {
            try (var paths = Files.walk(analysis.resolve(directory))) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    resources.put(analysis.relativize(path).toString().replace('\\', '/'), path);
                }
            }
        }
        resources.put("ai-automation-defaults.properties",
                root.resolve("taxonomy-portfolio/src/main/resources/ai-automation-defaults.properties"));
        List<Path> applications;
        try (var paths = Files.list(root.resolve("taxonomy-app/target"))) {
            applications = paths.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("taxonomy-app-") && n.endsWith(".jar")
                        && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
            }).toList();
        }
        assertThat(applications).hasSize(1);
        Set<String> foundClasses = new HashSet<>();
        Set<String> foundResources = new HashSet<>();
        try (var application = new ZipFile(applications.getFirst().toFile())) {
            var names = application.stream().map(e -> e.getName()).toList();
            for (String context : CONTEXTS) {
                assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-" + context + "-")
                        && n.endsWith(".jar")).toList()).as("one packaged %s library", context).hasSize(1);
            }
            for (String name : names) {
                if (name.startsWith("BOOT-INF/classes/")) {
                    String relative = name.substring("BOOT-INF/classes/".length());
                    assertThat(owners).as("no application copy of %s", relative).doesNotContainKey(relative);
                    assertThat(resources).as("no application copy of %s", relative).doesNotContainKey(relative);
                }
                if (!name.startsWith("BOOT-INF/lib/") || !name.endsWith(".jar")) continue;
                try (var nested = new ZipInputStream(application.getInputStream(application.getEntry(name)))) {
                    for (var entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry()) {
                        String relative = entry.getName();
                        if (owners.containsKey(relative)) {
                            assertThat(name).startsWith("BOOT-INF/lib/" + owners.get(relative) + "-");
                            assertThat(foundClasses.add(relative)).as("unique packaged class %s", relative).isTrue();
                        }
                        if (resources.containsKey(relative)) {
                            String owner = relative.equals("ai-automation-defaults.properties") ? "portfolio" : "analysis";
                            assertThat(name).startsWith("BOOT-INF/lib/taxonomy-" + owner + "-");
                            assertThat(foundResources.add(relative)).as("unique packaged resource %s", relative).isTrue();
                            assertThat(nested.readAllBytes()).isEqualTo(Files.readAllBytes(resources.get(relative)));
                        }
                    }
                }
            }
            assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/db/migration/"))).isTrue();
        }
        assertThat(foundClasses).containsExactlyInAnyOrderElementsOf(owners.keySet());
        assertThat(foundResources).containsExactlyInAnyOrderElementsOf(resources.keySet());
    }
}
