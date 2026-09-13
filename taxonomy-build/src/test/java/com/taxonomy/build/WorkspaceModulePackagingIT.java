package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceModulePackagingIT {
    @Test
    void bootApplicationContainsOneWorkspaceLibraryAndNoDuplicateWorkspaceClasses() throws Exception {
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
            assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-workspace-")
                    && n.endsWith(".jar")).toList()).hasSize(1);
            for (String part : List.of("workspace", "versioning", "editor")) {
                assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/com/taxonomy/" + part + "/"))).isFalse();
            }
            assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/db/migration/"))).isTrue();
        }
    }
}
