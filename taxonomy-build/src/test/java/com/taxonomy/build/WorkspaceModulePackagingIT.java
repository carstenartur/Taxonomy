package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
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
            var libraries = names.stream().filter(n -> n.startsWith("BOOT-INF/lib/taxonomy-workspace-")
                    && n.endsWith(".jar")).toList();
            assertThat(libraries).hasSize(1);
            var ownedClasses = new HashSet<String>();
            for (String name : names) {
                if (!name.startsWith("BOOT-INF/lib/") || !name.endsWith(".jar")) {
                    continue;
                }
                try (var library = new ZipInputStream(jar.getInputStream(jar.getEntry(name)))) {
                    for (var entry = library.getNextEntry(); entry != null; entry = library.getNextEntry()) {
                        String path = entry.getName();
                        if (path.endsWith(".class") && List.of("workspace", "versioning", "editor").stream()
                                .anyMatch(part -> path.startsWith("com/taxonomy/" + part + "/"))) {
                            assertThat(name).as("library owning %s", path).isEqualTo(libraries.getFirst());
                            assertThat(ownedClasses.add(path)).as("single occurrence of %s", path).isTrue();
                        }
                    }
                }
            }
            assertThat(ownedClasses).contains("com/taxonomy/editor/persistence/EditorJournal.class",
                    "com/taxonomy/workspace/storage/DslGitRepository.class",
                    "com/taxonomy/versioning/service/RepositoryStateService.class");
            for (String part : List.of("workspace", "versioning", "editor")) {
                assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/com/taxonomy/" + part + "/"))).isFalse();
            }
            assertThat(names.stream().anyMatch(n -> n.startsWith("BOOT-INF/classes/db/migration/"))).isTrue();
        }
    }
}
