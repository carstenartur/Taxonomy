package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureModulePackagingIT {
    @Test
    void bootApplicationShipsArchitectureOnlyInItsLibrary() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        List<Path> applications;
        try (var files = Files.list(root.resolve("taxonomy-app/target"))) {
            applications = files.filter(p -> p.getFileName().toString().startsWith("taxonomy-app-")
                    && p.getFileName().toString().endsWith(".jar")).toList();
        }
        assertThat(applications).hasSize(1);
        try (var jar = new ZipFile(applications.getFirst().toFile())) {
            var names = jar.stream().map(entry -> entry.getName()).toList();
            var libraries = names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-architecture-")
                    && n.endsWith(".jar")).toList();
            assertThat(libraries).hasSize(1);
            var owned = new HashSet<String>();
            for (String name : names) {
                if (!name.startsWith("BOOT-INF/lib/") || !name.endsWith(".jar")) continue;
                try (var library = new ZipInputStream(jar.getInputStream(jar.getEntry(name)))) {
                    for (var entry = library.getNextEntry(); entry != null; entry = library.getNextEntry()) {
                        String path = entry.getName();
                        if (path.startsWith("com/taxonomy/architecture/") && path.endsWith(".class")) {
                            assertThat(name).as("library owning %s", path).isEqualTo(libraries.getFirst());
                            assertThat(owned.add(path)).as("unique class %s", path).isTrue();
                        }
                    }
                }
            }
            assertThat(owned).contains("com/taxonomy/architecture/service/ArchitectureReportService.class",
                    "com/taxonomy/architecture/pipeline/ArchitectureViewPipeline.class",
                    "com/taxonomy/architecture/service/ArchitectureReportMetadataPort.class");
            assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/com/taxonomy/architecture/"))).isFalse();
            assertThat(names).contains("BOOT-INF/classes/com/taxonomy/composition/report/PreferenceArchitectureReportMetadata.class");
            assertThat(names).contains("BOOT-INF/classes/com/taxonomy/composition/report/ReportApiController.class");
            assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/db/migration/"))).isTrue();
        }
    }
}
